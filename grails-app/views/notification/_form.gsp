<%@ page import="com.cabolabs.ehrserver.notification.Notification" %><%@ page import="com.cabolabs.security.Organization" %>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'name', 'error')} required">
  <label class="control-label" for="name">
    <g:message code="notification.list.attr.name" />
    <span class="required-indicator">*</span>
  </label>
  <g:textField name="name" required="" value="${notificationInstance?.name}" class="form-control"/>
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'kind', 'error')} ">
  <label class="control-label" for="kind">
    <g:message code="notification.list.attr.kind" />
  </label>
  <select name="kind" class="form-control">
    <g:each var="c" in="${Notification.constraints.kind.inList}">
      <option value="${c}">${c}</option>
    </g:each>
  </select>
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'forSection', 'error')} ">
  <label class="control-label" for="forSection">
    <g:message code="notification.list.attr.forSection" />
  </label>
  <select name="forSection" class="form-control">
    <g:each var="c" in="${grailsApplication.controllerClasses.sort { it.logicalPropertyName }.logicalPropertyName - ['simpleCaptcha', 'rest', 'dbdoc', 'login', 'logout', 'test', 'stats', 'messaging', 'plan'] }">
      <option value="${c}">${c}</option>
    </g:each>
  </select>
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'forOrganization', 'error')} ">
  <label class="control-label" for="forOrganization">
    <g:message code="notification.list.attr.forOrganization" />
  </label>

  <sec:ifAnyGranted roles="ROLE_ADMIN">
    <g:select name="forOrganization" from="${Organization.list()}"
              optionKey="uid" optionValue="name" class="form-control"
              noSelection="${['': message(code:'defaut.select.selectOne')]}" />
  </sec:ifAnyGranted>
  <sec:ifNotGranted roles="ROLE_ADMIN">
    <g:selectWithCurrentUserOrganizations name="forOrganization" class="form-control" />
  </sec:ifNotGranted>
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'forUser', 'error')} ">
  <label class="control-label" for="forUser">
    <g:message code="notification.list.attr.forUser" />
  </label>
  <%--<g:field name="forUser" type="number" value="${notificationInstance.forUser}" class="form-control"/>--%>
  <g:select name="forUser" from="${users}"
              optionKey="id" optionValue="username" class="form-control"
              noSelection="${['': message(code:'defaut.select.selectOne')]}" />
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'language', 'error')} required">
  <label class="control-label" for="language">
    <g:message code="notification.list.attr.language" />
    <span class="required-indicator">*</span>
  </label>
  <g:select from="${['es','en', 'pt']}" name="language" class="form-control" required="" />
  <%--<g:textField name="language" required="" value="${notificationInstance?.language}" class="form-control"/>--%>
</div>

<div class="form-group ${hasErrors(bean: notificationInstance, field: 'text', 'error')} required">
  <label class="control-label" for="text">
    <g:message code="notification.list.attr.text" />
    <span class="required-indicator">*</span>
  </label>
  <g:textArea rows="3" name="text" required="" value="${notificationInstance?.text}" class="form-control"/>
</div>

<script>
$(function(){
  $('[name=kind]').on('change', function(e){
    console.log(this.value);
    if (this.value == 'email')
    {
      $('[name=forSection]').parent().hide();
    }
    else
    {
      $('[name=forSection]').parent().show();
    }
  });
});
</script>