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
            <p><i class="fas fa-clock"></i> <span id="lockTimer"></span></p>
            <#if error??> <span class="form-error-message">${error}</span></#if>
          </div>
        </div>
    </#if>
    <#if recordLockDuration??>
    <script type="text/javascript">
      $(document).ready(function() {
        const socket = new WebSocket(((window.location.protocol === "https:") ? "wss://" : "ws://") + window.location.host + "${request.contextPath}/web/socket/plugin/org.joget.marketplace.FormRecordLockingField?recordId=${recordId!}&formDefId=${formDefId!}&appId=" + UI.userview_app_id);
        
        let lockExpiresAt = parseDate('${recordLockExpiry!}');
        let lockMinutesLeft = +'${recordLockDuration!}';
        let lockSecondsLeft = lockMinutesLeft * 60;
        let idleTimeLimit = lockSecondsLeft * 1000;
        let countdown, checkExpiry;

        function resetIdleTimer() {
          lockExpiresAt = Date.now();
        }

        function startCountdown() {
          if (countdown) {
            return;
          }

          countdown = setInterval(() => {
            var minutes = Math.floor(lockSecondsLeft / 60);
            var seconds = lockSecondsLeft % 60;
            let timeLeft = '';

            if (minutes > 0) {
              timeLeft += minutes + 'm';

              if(seconds > 0) {
                timeLeft += ' ' + seconds + 's';
              }
            }
            else {
              timeLeft += seconds + 's';
            }

            $('#lockTimer').text(timeLeft);

            lockSecondsLeft--;

            if (lockSecondsLeft <= 0) {
              clearInterval(countdown);
              $('#lockTimer').text('0s');
            }
          }, 1000);
        }

        function stopCountdown() {
          if (countdown) {
            clearInterval(countdown);
            countdown = null;
          }
        }
        
        function restartCountdown() {
          stopCountdown();
          startCountdown();
        }

        restartCountdown();

        function startCheckExpiry() {
          if (checkExpiry) {
            return;
          }

          checkExpiry = setInterval(() => {
            let idleDuration = Date.now() - lockExpiresAt;

            if (socket.readyState === WebSocket.OPEN) {
              if (idleDuration > idleTimeLimit) {
                socket.close();
              } else {
                socket.send("active");
              }
            }
          }, idleTimeLimit);
        }

        function stopCheckExpiry() {
          if (checkExpiry) {
            clearInterval(checkExpiry);
            checkExpiry = null;
          }
        }

        function restartCheckExpiry() {
          stopCheckExpiry();
          startCheckExpiry();
        }

        restartCheckExpiry();

        socket.onmessage = (event) => {
          if (event.type === 'message') {
            const message = JSON.parse(event.data);
            lockExpiresAt = parseDate(message.lockExpiresAt);
            lockSecondsLeft = parseInt(message.lockSecondsLeft);
            restartCountdown();
            restartCheckExpiry();
          }
        }

        socket.onclose = (event) => {
          stopCheckExpiry();
          stopCountdown();
          $('#cancel').click();
        }

        document.addEventListener('mousemove', resetIdleTimer);
        document.addEventListener('keydown', resetIdleTimer);
        document.addEventListener('scroll', resetIdleTimer);
      });

      function parseDate(dateString) {
          dateString = dateString.replace(" ", "T");
          
          const date = new Date(dateString);

          if (isNaN(date.getTime())) {
              return Date.now();
          }
          
          return date;
      }
    </script>
    </#if>
</div>