package org.sakaiproject.webapi.beans;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single site membership read from the live realm (role + active flag), so an external system (WTM) can
 * register a missing membership before retrying a dropped grade. {@code role} is the raw Sakai realm role id
 * (e.g. "access", "Student") - the caller decides which roles it accepts.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteMemberRestBean {

    private String siteId;
    private String userId;
    private String role;
    private boolean active;

}
