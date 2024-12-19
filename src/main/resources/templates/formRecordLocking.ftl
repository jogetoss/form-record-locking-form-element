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
    <#if (element.properties.enableWebsocket! == 'true')>
        <input id="isEnabled" name="isEnabled" type="hidden" />
    </#if>

    <#--
    <#if (element.properties.readonly! == 'true' && element.properties.readonlyLabel! == 'true') >
        <div class="form-cell-value"><span>${value!?html}</span></div>
        <input id="${elementParamName!}" name="${elementParamName!}" type="hidden" value="${value!?html}" />
    <#else>    
        <input type="text" id="messageInput" placeholder="Enter message">
        <button id="sendButton">Send</button>
        <button id="closeButton">Close</button><br>
        <div id='output'></div>
    </#if>
    -->
    <div id='output'></div>

    <script>
    $(document).ready(function() {
<#--
        $("#sendButton").off("click");
        $("#closeButton").off("click");
    -->
        if ($("#isEnabled").length > 0) {

            const ws = new WebSocket(((window.location.protocol === "https:") ? "wss://" : "ws://") + window.location.host + "${request.contextPath}/web/socket/plugin/org.joget.marketplace.FormRecordLockingField");

            ws.onopen = function(event) {
                console.log(event);
                $("#output").append('Connection opened with timeStamp: ' + event.timeStamp + '<br/>');
            }; 
   
            // Tab close
            window.addEventListener("beforeunload", () => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify(jsonMsg('Tab closed'))); // Send session info
                    ws.close(1000, 'Tab closed');
                    console.log('WebSocket connection closed due to tab close');
                }
            });
            // Add an event listener for button
            document.querySelectorAll('.form-button').forEach( button => {
                button.addEventListener('click', () => {
                console.log('Button clicked');
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify(jsonMsg('Button clicked'))); // Send session info
                        ws.close(1000, 'Button clicked');
                        console.log('WebSocket connection closed due to  button click');
                    }
                });
            });
            ws.onmessage = function(event) {
                $("#output").append(event.data + '<br/>');
            }; 

            ws.onclose = function(event) {
                $("#output").append('Connection closed with timeStamp: ' + event.timeStamp + '<br/>');
                $("#output").append('WebSocket closed<br/>');
            }; 

            ws.onError = function(event) {
                $("#output").append("Error: " + event.data + '<br/>');
            };  
        
        } 
    });

    function jsonMsg(action){
        return {
            recordId: '${recordId!}',
            idColumn: '${idColumn!}',
            tableName: '${tableName!}',
            username: '${lockUsername!}',
            action: action,
            unlock: true,
        };
    }
    </script>
    <div style="clear:both;"></div>
</div>
