/******************************************************************************
 * Copyright 2015 sakaiproject.org Licensed under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/ECL-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.sakaiproject.webapi.controllers;

import org.apache.commons.lang3.StringUtils;

import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.messaging.api.UserMessagingService;
import org.sakaiproject.messaging.api.model.UserNotification;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.ToolConfiguration;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.webapi.beans.SiteMemberRestBean;
import org.sakaiproject.webapi.beans.SiteScheduledRestBean;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class SitesController extends AbstractSakaiApiController {

	@Autowired
	@Qualifier("org.sakaiproject.coursemanagement.api.CourseManagementService")
	private CourseManagementService cmService;

	@Autowired
	private ServerConfigurationService serverConfigurationService;

	@Autowired
	private UserMessagingService userMessagingService;

	@Autowired
	private SqlService sqlService;

	@GetMapping(value = "/users/{userId}/sites", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, List<Map<String, Object>>> getSites(@PathVariable String userId, @RequestParam Optional<Boolean> pinned)
        throws UserNotDefinedException {

		checkSakaiSession();

        List<String> pinnedSites = portalService.getPinnedSites();
        List<UserNotification> notifications = userMessagingService.getNotifications();

        return Map.of(
            "terms", cmService.getAcademicSessions().stream().map(as -> {
                    return Map.<String, Object>of("id", as.getEid(), "name", as.getTitle());
                }).collect(Collectors.toList()),
            "sites", siteService.getUserSites().stream().map(s -> {

                    if (pinned.isPresent() && pinned.get().equals(Boolean.TRUE) && !pinnedSites.contains(s.getId())) {
                        return null;
                    }

                    List<UserNotification> siteNotifications = notifications.stream().filter(n -> StringUtils.equals(n.getSiteId(), s.getId())).collect(Collectors.toList());

                    Map<String, Object> site = new HashMap<>();
                    site.put("siteId", s.getId());
                    site.put("title", s.getTitle());
                    // Include short description for sorting/display (trimmed; omit if blank)
                    String sd = StringUtils.trimToNull(s.getShortDescription());
                    if (sd != null) {
                        site.put("shortDescription", sd);
                    }
                    site.put("url", s.getUrl());
                    site.put("pinned", pinnedSites.contains(s.getId()));

                    site.put("image", s.getProperties().getProperty(Site.PROP_COURSE_IMAGE_URL));

                    site.put("tools", s.getPages().stream().map(sp -> {

                            List<ToolConfiguration> tools = sp.getTools();
                            if (tools.size() != 1) return null;
                            if (tools.get(0).getTool() == null) return null;
                            String url = serverConfigurationService.getPortalUrl() + "/site/" + s.getId() + "/tool/" + tools.get(0).getId();
                            String commonToolId = tools.get(0).getTool().getId();
                            boolean hasAlerts = siteNotifications.stream().anyMatch(sn -> !sn.getViewed() && StringUtils.equals(sn.getTool(), commonToolId));
                            return Map.of("id", commonToolId, "title", sp.getTitle(), "url", url, "iconClass", "si-" + commonToolId.replace(".", "-"), "hasAlerts", hasAlerts);
                        }).filter(Objects::nonNull).collect(Collectors.toList()));

                    if (StringUtils.equals(s.getType(), "course")) {
                        site.put("course", true);
                        site.put("term", s.getProperties().getProperty(Site.PROP_SITE_TERM));
                    } else if (StringUtils.equals(s.getType(), "project")) {
                        site.put("project", true);
                    }
                    return site;
                }).filter(Objects::nonNull).collect(Collectors.toList()));
	}

    /* Endpoint to fetch a SINGLE site membership (the user's current role in the site realm) so an external
       system (WTM) can register a missing membership before retrying a dropped grade. Reads the LIVE realm
       via Site.getMember - unlike /membership/scheduled it does not depend on user_audits_log, so it also
       surfaces enrolments created before go-live or via unaudited paths. 404 when the user is not a member. */
    @GetMapping(value = "/sites/{siteId}/members/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SiteMemberRestBean> getSiteMember(
        @PathVariable String siteId,
        @PathVariable String userId
    ) {

        checkSakaiSession();

        try {
            Site site = siteService.getSite(siteId);
            Member member = site.getMember(userId);
            if (member == null) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            Role role = member.getRole();
            String roleId = role != null ? role.getId() : null;
            SiteMemberRestBean bean = new SiteMemberRestBean(siteId, member.getUserId(), roleId, member.isActive());
            return new ResponseEntity<>(bean, HttpStatus.OK);
        } catch (IdUnusedException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("getSiteMember failed for siteId=" + siteId + " userId=" + userId + ": " + e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /* Endpoint to fetch a SINGLE site/course by its Sakai site id, in the same shape as /sites/scheduled, so
       an external system (WTM) can register a missing course before retrying a dropped grade. 404 when absent. */
    @GetMapping(value = "/sites/{siteId}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SiteScheduledRestBean> getSiteDetails(@PathVariable String siteId) {

        checkSakaiSession();

        // Lookup is by SITE_ID (the primary key), so no TYPE filter is applied: a caller that names an
        // exact site must be able to reconcile it even when its TYPE is unset (e.g. legacy/imported sites).
        // A "TYPE in ('project','course')" predicate would wrongly 404 such a site despite it being a real,
        // published, non-user, non-special site.
        String query = "SELECT SITE_ID, TITLE, CREATEDON, MODIFIEDON, SOFTLY_DELETED_DATE " +
            "FROM SAKAI_SITE WHERE SITE_ID = ?";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = sqlService.borrowConnection();
            conn.setReadOnly(true);
            ps = conn.prepareStatement(query);
            ps.setString(1, siteId);
            rs = ps.executeQuery();
            if (rs.next()) {
                SiteScheduledRestBean bean = new SiteScheduledRestBean(
                    rs.getString("SITE_ID"), rs.getString("TITLE"), rs.getString("CREATEDON"),
                    rs.getString("MODIFIEDON"), rs.getString("SOFTLY_DELETED_DATE"));
                return new ResponseEntity<>(bean, HttpStatus.OK);
            }
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("getSiteDetails failed for siteId=" + siteId + ": " + e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            try {
                if (rs != null) rs.close();
                if (ps != null) ps.close();
            } catch (SQLException e) {
                log.error("SQLException in finally block: " + e);
            }
            if (conn != null) {
                sqlService.returnConnection(conn);
            }
        }
    }
}
