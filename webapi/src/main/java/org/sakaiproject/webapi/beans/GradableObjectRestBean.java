package org.sakaiproject.webapi.beans;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single gradable object (assessment / external gradebook item) described well enough for an external
 * system (WTM) to register it as a reference before retrying a dropped grade. {@code entityId} is the same
 * value the {@code /grades/latest} feed exposes (EXTERNAL_ID, or the tail of REFERENCE for path-based ids),
 * and {@code toolId} is the gradebook item's EXTERNAL_APP_NAME.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GradableObjectRestBean {

    private String siteId;
    private String entityId;
    private String toolId;
    private String title;
    private Double pointsPossible;
    private String dueDate;

}
