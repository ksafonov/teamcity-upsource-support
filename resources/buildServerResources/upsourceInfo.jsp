<%@ include file="/include.jsp" %>
<style type="text/css">
    .rutarget_error {
        color: red;
    }

    .rutarget_review_open {
    }
    
    .rutarget_review_closed {
        color: gray;
    }
</style>
<c:choose>
    <c:when test="${error}">
        <div class="rutarget_error" title="${errorText}">error</div>
    </c:when>
    <c:otherwise>
        <c:if test="${reviewExists}">
            <a class="${reviewClosed ? "rutarget_review_closed" : "rutarget_review_open"}" href="${reviewLink}" title="${reviewTitle}">
                <c:out value="${reviewId}"/>
            </a>
        </c:if>
    </c:otherwise>
</c:choose>