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
            <h4 class="panel-title"><i class="fas fa-user-lock"></i> <span id="lockStatus">${element.properties.label}</span></h4>
          </div>
          <div class="panel-body">
            <p><i class="fas fa-user"></i> ${recordLockUsername!}</p>
            <p><i class="fas fa-clock"></i> <span id="lockTimer"></span></p>
            <#if error??> <span class="form-error-message">${error}</span></#if>
          </div>
        </div>
    </#if>
    <#if recordLockInPlace??>
    <script type="text/javascript">
      $(document).ready(function() {
        const socket = new WebSocket(((window.location.protocol === "https:") ? "wss://" : "ws://") + window.location.host + "${request.contextPath}/web/socket/plugin/org.joget.marketplace.FormRecordLockingField?recordId=${recordId!}&formDefId=${formDefId!}&appId=" + UI.userview_app_id);
        const owner = '${recordLockOwner?c}'.toLowerCase() === 'true';
        let lockExpiresAt, lockSecondsLeft, idleMillisLimit, lastActiveTime, isActive;
        const urlParams = UrlUtil.getUrlParams(window.location.href);

        function isExpired() {
          return new Date() >= lockExpiresAt;
        }

        function resetActivity() {
          lastActiveTime = new Date();
          isActive = true;
        }

        function startCheckExpiry() {
          if (window.checkExpiry) {
            return;
          }

          window.checkExpiry = window.setInterval(() => {
            const inActivePeriod = new Date() - lastActiveTime;
            
            if (inActivePeriod > idleMillisLimit) {
                isActive = false;
            }

            if (isExpired() && socket.readyState === WebSocket.OPEN) {
                if (owner) {
                  isActive ? socket.send("") : socket.close();
                }
                else {
                  socket.send("");
                }
            }
          }, 1000);
        }

        function stopCheckExpiry() {
          // if (checkExpiry) {
            window.clearInterval(window.checkExpiry);
            delete window.checkExpiry;
          // }
        }

        function restartCheckExpiry() {
          stopCheckExpiry();
          startCheckExpiry();
        }

        function resetExpiry(expiresAt, secondsLeft, idleSecondsLimit) {
          lockExpiresAt = new Date(expiresAt);
          lockSecondsLeft = parseInt(secondsLeft);
          idleMillisLimit = parseInt(idleSecondsLimit) * 1000;

          if (lockExpiresAt.getTime() == 'NaN' && lockSecondsLeft == 0 && socket.readyState === WebSocket.OPEN) {
            socket.close();
            return;
          }

          if (!owner) {
            lockExpiresAt.setSeconds(lockExpiresAt.getSeconds() + 5);
            lockSecondsLeft += 5;
          }

          restartCheckExpiry();
          resetActivity();
        }

        resetExpiry('${recordLockExpiry!}', '${lockSecondsLeft!}', '${idleSecondsLimit!}');

        function startCountdown() {
          if (window.countdown) {
            return;
          }

          window.countdown = window.setInterval(() => {
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
              stopCountdown();
            }
          }, 1000);
        }

        function stopCountdown() {
          // if (countdown) {
            window.clearInterval(window.countdown);
            delete window.countdown;
            $('#lockTimer').text('0s');
          // }
        }
        
        function restartCountdown() {
          stopCountdown();
          startCountdown();
        }

        restartCountdown();

        socket.onmessage = (event) => {
          if (event.type === 'message') {
            const message = JSON.parse(event.data);
            resetExpiry(message.lockExpiresAt, message.lockSecondsLeft, message.idleSecondsLimit);
            restartCountdown();
          }
        }

        socket.onclose = (event) => {
          stopCheckExpiry();
          stopCountdown();
          isActive = false;

          if(owner) {
            $('#submit').remove();
          }

          $('#lockStatus').text('Record Unlocked');
          $('.formRecordLockContainer .fa-user-lock').removeClass('fa-user-lock').addClass('fa-lock-open');
          $('.formRecordLockContainer .panel-body').remove();
        }

        document.addEventListener('mousemove', resetActivity);
        document.addEventListener('keydown', resetActivity);
        document.addEventListener('scroll', resetActivity);
        document.addEventListener('click', resetActivity);

        window.addEventListener('beforeunload', function (event) {
          stopCheckExpiry();
          stopCountdown();
          isActive = false;

          if (socket.readyState === WebSocket.OPEN) {
            socket.close();
          }
        });
      });
    </script>
    </#if>
</div>