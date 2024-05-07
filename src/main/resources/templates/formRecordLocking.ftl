<div class="form-cell" ${elementMetaData!}>
    <link rel="stylesheet" href="${request.contextPath}/plugin/org.joget.marketplace.FormRecordLockingField/css/FormRecordLockingField.css">
    
    <#if elementMetaData != "">
        <span class="form-floating-label"><i class="fas fa-lock"></i></span>
        <div class="panel panel-default formRecordLockContainer">
          <div class="panel-heading">
            <h4 class="panel-title"><i class="fas fa-user-lock"></i> ${element.properties.label}</h4>
          </div>
          <div class="panel-body">
            <p><i class="fas fa-user"></i> User</p>
            <p><i class="fas fa-clock"></i> X m</p>
          </div>
        </div>
    </#if>

    <#if recordLockNew??> 
        <div class="panel panel-default formRecordLockContainer">
          <div class="panel-heading">
            <h4 class="panel-title"><i class="fas fa-user-lock"></i> ${element.properties.label}</h4>
          </div>
          <div class="panel-body">
            <p><i class="fas fa-clock"></i> ${recordLockDuration!} m</p>
          </div>
        </div>
    </#if>

    <#if recordLockInPlace??>   
        <#if removeSaveButton??>
            <script type="text/javascript">
            $(function(){
                $("input[type='submit']").remove();
            });
          </script>
        </#if>
        <div class="panel panel-default formRecordLockContainer">
          <div class="panel-heading">
            <h4 class="panel-title"><i class="fas fa-user-lock"></i> ${element.properties.label}</h4>
          </div>
          <div class="panel-body">
            <p><i class="fas fa-user"></i> ${recordLockUsername!}</p>
            <p><i class="fas fa-clock"></i> ${recordLockDurationLeft!}</p>
            <#if error??> <span class="form-error-message">${error}</span></#if>
          </div>
        </div>
    </#if> 
</div>
