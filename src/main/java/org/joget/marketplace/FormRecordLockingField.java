package org.joget.marketplace;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Session;

import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.lib.DatabaseUpdateTool;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.FormBuilderPaletteElement;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.directory.model.User;
import org.joget.directory.model.service.ExtDirectoryManager;
import org.joget.plugin.base.PluginWebSocket;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.springframework.context.ApplicationContext;
import org.joget.workflow.model.WorkflowAssignment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class FormRecordLockingField extends Element implements FormBuilderPaletteElement,PluginWebSocket {
    
    // Store all active WebSocket sessions
    private static final long TIMEOUT = 10000; // 10 seconds
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final ConcurrentHashMap<Session, Long> sessionLastHeartbeatMap = new ConcurrentHashMap<>();
    private static boolean monitorStarted = false;

    @Override
    public String getName() {
        return "Form Record Locking";
    }

    @Override
    public String getVersion() {
        return "8.0.1";
    }

    @Override
    public String getDescription() {
        return "Form Record Locking Field Element to prevent concurrent edit";
    }

    @Override
    public String renderTemplate(FormData formData, Map dataModel) {
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) appContext.getBean("directoryManager");
        
        final String primaryKey = formData.getPrimaryKeyValue();
        
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        final String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), getPropertyString("formDefId"));
        final String idColumn = getPropertyString("id");
        
        if (primaryKey != null && !primaryKey.isEmpty()) {
            LogUtil.debug(getClassName(), "FormRecordLockingField: Record - " + primaryKey);

            final String value = FormUtil.getElementPropertyValue(this, formData); //obtain lock record
            final String currentUsername = workflowUserManager.getCurrentUsername();
            final int lockDuration = Integer.parseInt(getPropertyString("lockDuration"));
            //form load
            if (!isLockActive(value, currentUsername)) {
                //no lock is active
                if (!currentUsername.equalsIgnoreCase("roleAnonymous")) {
                    setLock(primaryKey, currentUsername, lockDuration, tableName, idColumn, true);

                    dataModel.put("recordLockNew", true);
                    dataModel.put("recordLockDuration", lockDuration);
                }
            } else {
                //lock is active
                if (getLockUsername(value).equalsIgnoreCase(currentUsername)) {
                    //lock is owned by current user
                    dataModel.put("recordLockOwner", true);
                    //future TODO: allow renewal by same user?
                } else {
                    //lock is not owned by current user
                    dataModel.put("recordLockOwner", false);
                    dataModel.put("removeSaveButton", true);
                }
                
                User lockUser = directoryManager.getUserByUsername(getLockUsername(value));
                String userFullname = getPropertyString("displayNameFormat").equalsIgnoreCase("firstLast") 
                        ? lockUser.getFirstName() + " " + lockUser.getLastName()
                        : lockUser.getLastName() + " " + lockUser.getFirstName();
                
                dataModel.put("recordLockUsername", userFullname);
                dataModel.put("recordLockInPlace", true);
                dataModel.put("recordLockExpiry", getLockExpiry(value));
                dataModel.put("recordLockDurationLeft", getLockExpiryDurationLeft(value));
            }
            if (isEnabled()){
                dataModel.put("recordId", primaryKey);
                dataModel.put("formDefId", getPropertyString("formDefId"));
            }
        }
        
        //if lock exists
        //  prevent editing
        //else
        //  update form record to lock for X mins
        
//        FormRecordLockingValidator validator = new FormRecordLockingValidator();
//        boolean recordLockInPlace = validator.validateFormRecordLock(formData, getPropertyString("id"), getPropertyString("errorMessage"));
//        dataModel.put("recordLockInPlace", recordLockInPlace);

        return FormUtil.generateElementHtml(this, formData, "formRecordLocking.ftl", dataModel);
    }
    
    public String getLockExpiryDurationLeft(String value) {
        if (value != null && !value.isEmpty()) {
            if (isEnabled() && getLockExpiry(value).isEmpty()) {
                return getPropertyString("lockDuration") + " m";
            }
            try {
                Date lockDateExpiry = getDateFormat().parse(getLockExpiry(value));
                
                long diff = lockDateExpiry.getTime() - (new Date()).getTime();
                long diffSeconds = diff / 1000 % 60;
                long diffMinutes = diff / (60 * 1000) % 60;
                
                return (diffMinutes > 0) 
                        ? diffMinutes + " m " + diffSeconds + " s"
                        : diffSeconds + " s";
            } catch(Exception ex) {
                //unable to determine, assume no lock is active, return false
                return "";
            }
        } else {
            return ""; //no lock is in place
        }
    }
    
    public String getLockExpiry(String value) {
        if (value != null && !value.isEmpty()) {
            try {
                return value.split(";")[0]; //timestamp
            } catch(Exception ex) {
                //unable to determine, assume no lock is active, return false
                return "";
            }
        } else {
            return ""; //no lock is in place
        }
    }
    
    public String getLockUsername(String value) {
        if (value != null && !value.isEmpty()) {
            try {
                return value.split(";")[1]; //username
            } catch(Exception ex) {
                //unable to determine, assume no lock is active, return false
                return "";
            }
        } else {
            return ""; //no lock is in place
        }
    }
    
    public boolean isLockActive(String value, String currentUsername) {
        if (value != null && !value.isEmpty()) {
            // web socket is enabled
            if (isEnabled() && getLockExpiry(value).isEmpty()) {
                return !getLockUsername(value).equals(currentUsername);
            }
            try {
                Date lockDateExpiry = getDateFormat().parse(getLockExpiry(value));
                
                boolean lockDateExpiryActive = lockDateExpiry.after(new Date());
                LogUtil.debug(getClassName(), "FormRecordLockingField: Lock State Active : " + lockDateExpiryActive);

                return lockDateExpiryActive;
            } catch(Exception ex) {
                //unable to determine, assume no lock is active, return false
                LogUtil.debug(getClassName(), "FormRecordLockingField: Lock State Active : None Found");
                return false;
            }
        } else {
            return false; //no lock is in place
        }
    }
    
    /**
     * Set the form record lock.
     * If web socket is enabled: 
     * a)on new lock, set the remaining lock time as lock duration
     * b)on heartbeat undetected, start the lock countdown
     * @param recordId
     * @param username
     * @param duration
     * @param tableName
     * @param idColumn
     * @param recordNew
     */
    public void setLock(final String recordId, String username, int duration, String tableName, String idColumn, boolean recordNew){
        Date lockUntilTime = addMinutesToDate(duration, new Date());
        
        String lockTimeStr = getDateFormat().format(lockUntilTime);
        if (recordNew && isEnabled()){
            lockTimeStr = "";
        }
        final String value = lockTimeStr + ";" + username;
        
        //TODO: update same form record
        WorkflowAssignment ass = new WorkflowAssignment();
        ass.setProcessId(recordId);
        
        Map propertiesMap = new HashMap();
        propertiesMap.put("workflowAssignment", ass);
        propertiesMap.put("jdbcDatasource", "default");
        propertiesMap.put("query", "UPDATE app_fd_" + tableName + " SET c_" + idColumn + " = '" + value + "' WHERE id = '" + recordId + "'");
        new DatabaseUpdateTool().execute(propertiesMap);
        
        LogUtil.debug(getClassName(), "FormRecordLockingField: Record - " + recordId + " - Apply Lock : " + value + " - Duration (min)" + duration);
    }
    
    private SimpleDateFormat getDateFormat() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }
    
    public FormRowSet formatData(FormData formData) {
        //form save
        String id = getPropertyString(FormUtil.PROPERTY_ID);
        String value = FormUtil.getElementPropertyValue(this, formData);
        if (id != null && value != null) {
            // set value into Properties and FormRowSet object
            FormRow result = new FormRow();
            result.setProperty(id, value);
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(result);

            return rowSet;
        }

        return null;
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getFormBuilderTemplate() {
        return "<label class='label'>Form Record Locking</label>";
    }

    @Override
    public String getLabel() {
        return "Form Record Locking";
    }

    @Override
    public String getPropertyOptions() {
        String formDefField = null;
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        if (appDef != null) {
            String formJsonUrl = "[CONTEXT_PATH]/web/json/console/app/" + appDef.getId() + "/" + appDef.getVersion() + "/forms/options";
            formDefField = "{name:'formDefId',label:'@@form.defaultformoptionbinder.formId@@',type:'selectbox',options_ajax:'" + formJsonUrl + "',required : 'True'}";
        } else {
            formDefField = "{name:'formDefId',label:'@@form.defaultformoptionbinder.formId@@',type:'textfield',required : 'True'}";
        }
        
        return AppUtil.readPluginResource(getClass().getName(), "/properties/form/formRecordLocking.json", new Object[]{formDefField}, true, "messages/form/formRecordLocking");
        
        /*
        TODO: how to hardcode a validator?
        
        {
            "name": "validator",
            "type": "Hidden",
            "value": {
                "className" : "org.joget.marketplace.FormRecordLockingValidator",
                "properties" : {"message" : "someone is using it now"}
            }
        },        
        */
    }

    @Override
    public String getFormBuilderCategory() {
        return "Marketplace";
    }

    @Override
    public int getFormBuilderPosition() {
        return 100;
    }

    @Override
    public String getFormBuilderIcon() {
        return "<i class=\"fas fa-lock\"></i>";
    }
    
    /*
    *  Convenience method to add a specified number of minutes to a Date object
    *  From: http://stackoverflow.com/questions/9043981/how-to-add-minutes-to-my-date
    *  @param  minutes  The number of minutes to add
    *  @param  beforeTime  The time that will have minutes added to it
    *  @return  A date object with the specified number of minutes added to it 
    */
    private static Date addMinutesToDate(int minutes, Date beforeTime){
        final long ONE_MINUTE_IN_MILLIS = 60000; //millisecs
        return new Date(beforeTime.getTime() + (minutes * ONE_MINUTE_IN_MILLIS));
    }

    @Override
    public void onOpen(Session session) {
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        try {
            session.getBasicRemote().sendText("Connection established");
            session.getUserProperties().put("currentUser", workflowUserManager.getCurrentUsername());
            LogUtil.debug(getClassName(), "Websocket connection established: " + session.getId());
            sessionLastHeartbeatMap.put(session, System.currentTimeMillis());
            
            // start heartbeat monitor (only once)
            startHeartbeatMonitorIfNeeded();
            
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Websocket connection error: onOpen");
        }
    }

    @Override
    public void onMessage(String message, Session session) {
        try {
            switch (message) {
                case "badump":
                    // Update the last heartbeat timestamp
                    sessionLastHeartbeatMap.put(session, System.currentTimeMillis());
                    LogUtil.debug(getClassName(), "Heartbeat received:" + System.currentTimeMillis() + session.getId());
                    break;
//                case "idle":
//                    LogUtil.debug(getClassName(), "Unlock record due to idle time.");
//                    updateDatabase(session, false, true);
//                    break;
//                case "cancel idle":
//                    LogUtil.debug(getClassName(), "Reset record");
//                    updateDatabase(session, false, false);
//                    break;
                default:
                    // Parse the JSON message using org.json
                    JSONObject jsonMessage = new JSONObject(message);
                    boolean unlock = jsonMessage.getBoolean("unlock");
                    LogUtil.debug(getClassName(), "message:" + message);
                    if (!unlock) {
                        session.getUserProperties().put("recordId", jsonMessage.getString("recordId"));
                        session.getUserProperties().put("appId", jsonMessage.getString("appId"));
                        session.getUserProperties().put("formDefId", jsonMessage.getString("formDefId"));
                    }
                    else {
                        LogUtil.debug(getClassName(), "sessionInfo:" + getSessionInfo(session));
                        updateDatabase(session, true);
                    }
                    break;
            }
            session.getBasicRemote().sendText("Server received: " + message);
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Websocket connection error: onMessage");
        } catch (JSONException e) {
            LogUtil.error(getClassName(), e, "Websocket connection error: onMessage");
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Websocket connection error: onMessage");
        }
    }

    @Override
    public void onClose(Session session) {
        sessionLastHeartbeatMap.remove(session);
        LogUtil.debug(getClassName(), "Websocket connection closed");
    }
    
    @Override
    public void onError(Session session, Throwable throwable) {
        LogUtil.error(getClassName(), throwable, "");
        sessionLastHeartbeatMap.remove(session);
        try {
            session.close(); // Ensure the session is closed
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Websocket connection error: onError");
        }
    }

    public boolean isEnabled() {
        return "true".equalsIgnoreCase(getPropertyString("enableWebsocket"));
    }

//    private void updateDatabase(Session session, boolean unlockMode, boolean idleMode) throws JSONException, Exception {
    private void updateDatabase(Session session, boolean unlockMode) throws JSONException, Exception {
        String recordId = session.getUserProperties().get("recordId").toString();
        String formDefId = session.getUserProperties().get("formDefId").toString();
        String appId = session.getUserProperties().get("appId").toString();
        //Load existing record
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        AppDefinition appDef = appService.getPublishedAppDefinition(appId);
        FormRowSet rowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, recordId);
        FormRow row = new FormRow();
        if (!rowSet.isEmpty()) {
            row = rowSet.get(0);
        }

        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormDefinition formDef = formDefinitionDao.loadById(formDefId, appDef);
        Element settings = getElementByClassName(new JSONObject(formDef.getJson()), FormRecordLockingField.class.getName());
        
        String currentUser = session.getUserProperties().get("currentUser").toString();
        String lockUser = getLockUsername(row.get(settings.getProperty("id")).toString());
        int lockDuration = Integer.parseInt(settings.getPropertyString("lockDuration"));
//        if (idleMode) {
//            lockDuration = Integer.parseInt(settings.getPropertyString("idleTimeout"));
//        }
        if (lockUser.isEmpty() || lockUser.equals(currentUser)) {
            if (!unlockMode && getLockExpiryDurationLeft(row.get(settings.getProperty("id")).toString()).isEmpty() && !currentUser.equals("roleAnonymous")) {
                LogUtil.info(getClassName(), "Reset record lock with id=" + recordId + "for user=" + currentUser);
                setLock(recordId, currentUser, lockDuration, formDef.getTableName(), settings.getPropertyString("id"), unlockMode);
            } 
            else {
                LogUtil.info(getClassName(), "Unlock record with id=" + recordId);

                row.setProperty(settings.getPropertyString("id"), "");
                appService.storeFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, rowSet, recordId);
            }
        }
    }

    private Element getElementByClassName(JSONObject obj, String className) {
        if (obj != null && className != null && !className.isEmpty()) {
            try {
                if (obj.has(FormUtil.PROPERTY_CLASS_NAME) &&
                    className.equals(obj.getString(FormUtil.PROPERTY_CLASS_NAME))) {
                    return FormUtil.parseElementFromJsonObject(obj, null);
                }

                if (!obj.isNull(FormUtil.PROPERTY_ELEMENTS)) {
                    JSONArray elements = obj.getJSONArray(FormUtil.PROPERTY_ELEMENTS);
                    if (elements != null && elements.length() > 0) {
                        for (int i = 0; i < elements.length(); i++) {
                            JSONObject childObj = elements.getJSONObject(i);

                            Element child = getElementByClassName(childObj, className);
                            if (child != null) {
                                return child;
                            }
                        }
                    }
                }
            }
            catch (Exception e) {}
        }

        return null;
    }

    private synchronized void startHeartbeatMonitorIfNeeded() {
        if (!monitorStarted) {
            monitorStarted = true;
            startHeartbeatMonitor();
        }
    }
    
    private String getSessionInfo(Session session) {
        StringBuilder sessionInfo = new StringBuilder();
        session.getUserProperties().entrySet().forEach(entry -> {
            sessionInfo.append(entry.getKey()).append(": ").append(entry.getValue()).append(", ");
        });
    
        // Remove the last comma and space if present
        if (sessionInfo.length() > 0) {
            sessionInfo.setLength(sessionInfo.length() - 2);
        }
        return sessionInfo.toString();
    }
    
    public String getIdleTime(String value) {
        if (value != null && !value.isEmpty()) {
            return value;
        } else {
            return "60"; // default value is 60 minutes
        }
    }

    public void startHeartbeatMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Session session : sessionLastHeartbeatMap.keySet()) {
                Long lastBeat = sessionLastHeartbeatMap.get(session);
                LogUtil.debug(getClassName(), "Last heartbeat:" + lastBeat);
                if (lastBeat == null || (now - lastBeat > TIMEOUT || session.getUserProperties() == null)) {
                    try {
                        updateDatabase(session, false);
                        session.close(new CloseReason(CloseCodes.GOING_AWAY, "Client unresponsive"));
                        LogUtil.debug(getClassName(), "Session closed due to inactivity: " + session.getId());
                    } catch (IOException e) {
                        LogUtil.error(getClassName(), e, "Scheduler error");
                        monitorStarted = false;
                    } catch (JSONException e) {
                        LogUtil.error(getClassName(), e, "Scheduler error");
                        monitorStarted = false;
                    } catch (Exception e) {
                        LogUtil.error(getClassName(), e, "Scheduler error");
                        monitorStarted = false;
                    }
                }
            }
        }, TIMEOUT, TIMEOUT, TimeUnit.MILLISECONDS);
        LogUtil.info(getClassName(), "Heartbeat monitor started");
    }
}
