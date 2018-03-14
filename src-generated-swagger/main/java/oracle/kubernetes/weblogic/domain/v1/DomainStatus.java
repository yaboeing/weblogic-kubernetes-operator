//Copyright 2017, 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
//Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.v1;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.joda.time.DateTime;


/**
 * DomainStatus represents information about the status of a domain. Status may trail the actual state of a system.
 * 
 */
public class DomainStatus {

    /**
     * Current service state of domain.
     * 
     */
    @SerializedName("conditions")
    @Expose
    @Valid
    private List<DomainCondition> conditions = new ArrayList<DomainCondition>();
    /**
     * A human readable message indicating details about why the domain is in this condition.
     * 
     */
    @SerializedName("message")
    @Expose
    private String message;
    /**
     * A brief CamelCase message indicating details about why the domain is in this state.
     * 
     */
    @SerializedName("reason")
    @Expose
    private String reason;
    /**
     * Status of WebLogic servers in this domain.
     * 
     */
    @SerializedName("status")
    @Expose
    @Valid
    private List<ServerStatus> status = new ArrayList<ServerStatus>();
    /**
     * RFC 3339 date and time at which the operator started the domain.  This will be when the operator begins processing and will precede when the various servers or clusters are available.
     * 
     */
    @SerializedName("startTime")
    @Expose
    private DateTime startTime;

    /**
     * Current service state of domain.
     * @return domain conditions
     */
    public List<DomainCondition> getConditions() {
        return conditions;
    }

    /**
     * Current service state of domain.
     * @param conditions domain conditions
     */
    public void setConditions(List<DomainCondition> conditions) {
        this.conditions = conditions;
    }

    /**
     * Current service state of domain.
     * @param conditions domain conditions
     * @return this
     */
    public DomainStatus withConditions(List<DomainCondition> conditions) {
        this.conditions = conditions;
        return this;
    }

    /**
     * A human readable message indicating details about why the domain is in this condition.
     * @return message
     */
    public String getMessage() {
        return message;
    }

    /**
     * A human readable message indicating details about why the domain is in this condition.
     * @param message message
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * A human readable message indicating details about why the domain is in this condition.
     * @param message message
     * @return this
     */
    public DomainStatus withMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * A brief CamelCase message indicating details about why the domain is in this state.
     * @return reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * A brief CamelCase message indicating details about why the domain is in this state.
     * @param reason reason
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * A brief CamelCase message indicating details about why the domain is in this state.
     * @param reason reason
     * @return this
     */
    public DomainStatus withReason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * Status of WebLogic servers in this domain.
     * @return server status
     */
    public List<ServerStatus> getStatus() {
        return status;
    }

    /**
     * Status of WebLogic servers in this domain.
     * @param status server status
     */
    public void setStatus(List<ServerStatus> status) {
        this.status = status;
    }

    /**
     * Status of WebLogic servers in this domain.
     * @param status server status
     * @return this
     */
    public DomainStatus withStatus(List<ServerStatus> status) {
        this.status = status;
        return this;
    }

    /**
     * RFC 3339 date and time at which the operator started the domain.  This will be when the operator begins processing and will precede when the various servers or clusters are available.
     * @return start time
     */
    public DateTime getStartTime() {
        return startTime;
    }

    /**
     * RFC 3339 date and time at which the operator started the domain.  This will be when the operator begins processing and will precede when the various servers or clusters are available.
     * @param startTime start time
     */
    public void setStartTime(DateTime startTime) {
        this.startTime = startTime;
    }

    /**
     * RFC 3339 date and time at which the operator started the domain.  This will be when the operator begins processing and will precede when the various servers or clusters are available.
     * @param startTime start time
     * @return this
     */
    public DomainStatus withStartTime(DateTime startTime) {
        this.startTime = startTime;
        return this;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append("conditions", conditions).append("message", message).append("reason", reason).append("status", status).append("startTime", startTime).toString();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(reason).append(startTime).append(conditions).append(message).append(status).toHashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof DomainStatus) == false) {
            return false;
        }
        DomainStatus rhs = ((DomainStatus) other);
        return new EqualsBuilder().append(reason, rhs.reason).append(startTime, rhs.startTime).append(conditions, rhs.conditions).append(message, rhs.message).append(status, rhs.status).isEquals();
    }

}
