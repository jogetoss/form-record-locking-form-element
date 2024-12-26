package org.joget.marketplace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.joget.apps.app.lib.DatabaseUpdateTool;
import org.joget.apps.app.model.AppDefinition;
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
import org.joget.workflow.model.service.WorkflowUserManager;
import org.springframework.context.ApplicationContext;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.plugin.base.PluginWebSocket;
import javax.websocket.Session;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.dao.FormDefinitionDao;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.joget.workflow.util.WorkflowUtil;

public class FormRecordLockingField extends Element implements FormBuilderPaletteElement, PluginWebSocket {

    @Override
    public String getName() {
        return "Form Record Locking";
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return "Form Record Locking Field Element to prevent concurrent edit";
    }

    @Override
    public String renderTemplate(FormData formData, Map dataModel) {
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        HttpServletRequest request = WorkflowUtil.getHttpServletRequest();
        
        final String primaryKey = formData.getPrimaryKeyValue();
        
        if (primaryKey != null && !primaryKey.isEmpty() && request.getParameter("_mode").equalsIgnoreCase("edit")) {
            LogUtil.debug(getClassName(), "FormRecordLockingField: Record - " + primaryKey);

            String value = FormUtil.getElementPropertyValue(this, formData); //obtain lock record
            final String currentUsername = workflowUserManager.getCurrentUsername();
            int lockDuration = Integer.parseInt(getPropertyString("lockDuration"));
            
            //form load
            if (!isLockActive(value)) {
                //no lock is active
                if (!currentUsername.equalsIgnoreCase("roleAnonymous")) {
                    value = setLock(primaryKey, currentUsername, lockDuration, getPropertyString("formDefId"));

                    dataModel.put("recordLockNew", true);
                    dataModel.put("recordLockOwner", true);
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
            }
            
            dataModel.put("recordLockDuration", lockDuration);
            dataModel.put("recordId", primaryKey);
            dataModel.put("formDefId", getPropertyString("formDefId"));
            dataModel.put("recordLockUsername", getLockUserFullname(value));
            dataModel.put("recordLockInPlace", true);
            dataModel.put("recordLockExpiry", getLockExpiry(value));
            dataModel.put("recordLockDurationLeft", getLockExpiryDurationLeft(value));
            dataModel.put("lockSecondsLeft", durationToSeconds(getLockExpiryDurationLeft(value)));
            dataModel.put("idleSecondsLimit", getPropertyString("idleSecondsLimit"));
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
    
    public boolean isLockActive(String value) {
        if (value != null && !value.isEmpty()) {
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
    
    public String setLock(final String recordId, String username, int duration, String formDefId){        
        final String value = makeLockValue(recordId, username, duration);
        
        //TODO: update same form record
        WorkflowAssignment ass = new WorkflowAssignment();
        ass.setProcessId(recordId);
        
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        final String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), formDefId);
        
        Map propertiesMap = new HashMap();
        propertiesMap.put("workflowAssignment", ass);
        propertiesMap.put("jdbcDatasource", "default");
        propertiesMap.put("query", "UPDATE app_fd_" + tableName + " SET c_" + getPropertyString("id") + " = '" + value + "' WHERE id = '" + recordId + "'");
        new DatabaseUpdateTool().execute(propertiesMap);
        
        LogUtil.debug(getClassName(), "FormRecordLockingField: Record - " + recordId + " - Apply Lock : " + value + " - Duration (min)" + duration);

        return value;
    }
    
    private SimpleDateFormat getDateFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf;
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
        LogUtil.debug(getClassName(), "opened");
    }

    @Override
    public void onMessage(String status, Session session) {
        LogUtil.debug(getClassName(), "message");
        JSONObject message = refreshLock(session);

        try {
            session.getBasicRemote().sendText(message.toString());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(Session session) {
        LogUtil.debug(getClassName(), "closed");
        unLock(session);

        try {
            session.getBasicRemote().sendText("closed");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void onError(Session session, Throwable throwable) {
        unLock(session);
    }

    public JSONObject refreshLock(Session session){
        Map<String, List<String>> params = session.getRequestParameterMap();
        String lockValue = null;
        String idleSecondsLimit = null;

        if (params.containsKey("appId") &&
            params.containsKey("recordId") &&
            params.containsKey("formDefId")) {
            
            String appId = params.get("appId").get(0);
            String recordId = params.get("recordId").get(0);
            String formDefId = params.get("formDefId").get(0);

            if (!appId.isEmpty() &&
                !recordId.isEmpty() &&
                !formDefId.isEmpty()) {
                
                AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                AppDefinition appDef = appService.getPublishedAppDefinition(appId);
                FormRowSet rowSet = getLockedRowSet(appService, appDef, formDefId, recordId);
                Element settings = getSettings(appDef, formDefId);
                
                ApplicationContext appContext = AppUtil.getApplicationContext();
                WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
                String username = workflowUserManager.getCurrentUsername();

                lockValue = getLockedValue(rowSet, settings);
                idleSecondsLimit = settings.getPropertyString("idleSecondsLimit");

                LogUtil.debug(getClassName(), "lockValue: " + lockValue);

                if (username.equals(getLockUsername(lockValue))) {
                    if (!isLockActive(lockValue)) {
                        lockValue = saveLockValue(appService, appDef, settings, formDefId, recordId, rowSet, username);
                    }
                }
            }
        }

        JSONObject message = new JSONObject();
        message.put("lockExpiresAt", getLockExpiry(lockValue));
        message.put("lockSecondsLeft", durationToSeconds(getLockExpiryDurationLeft(lockValue)));
        message.put("idleSecondsLimit", idleSecondsLimit);

        return message;
    }

    private void unLock(Session session){
        Map<String, List<String>> params = session.getRequestParameterMap();

        if (params.containsKey("appId") &&
            params.containsKey("recordId") &&
            params.containsKey("formDefId")) {
            
            String appId = params.get("appId").get(0);
            String recordId = params.get("recordId").get(0);
            String formDefId = params.get("formDefId").get(0);

            if (!appId.isEmpty() &&
                !recordId.isEmpty() &&
                !formDefId.isEmpty()) {
                
                AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                AppDefinition appDef = appService.getPublishedAppDefinition(appId);
                FormRowSet rowSet = getLockedRowSet(appService, appDef, formDefId, recordId);
                Element settings = getSettings(appDef, formDefId);

                ApplicationContext appContext = AppUtil.getApplicationContext();
                WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
                String username = workflowUserManager.getCurrentUsername();

                String lockValue = getLockedValue(rowSet, settings);

                if (username.equals(getLockUsername(lockValue))) {
                    saveLockValue(appService, appDef, settings, formDefId, recordId, rowSet, null);
                    LogUtil.debug(getClassName(), "unlocked");
                }
            }
        }
    }

    private String saveLockValue(AppService appService, AppDefinition appDef, Element settings, String formDefId, String recordId, FormRowSet rowSet, String username) {
        FormRow row = rowSet.get(0);
        
        String lockValue = "";
        if (username != null && !username.isEmpty()) {
            // when username is provided, it means reset the lock timer. otherwise, unlock it
            int lockDuration = Integer.parseInt(settings.getPropertyString("lockDuration"));
            lockValue = makeLockValue(recordId, username, lockDuration);
        }

        row.setProperty(settings.getPropertyString("id"), lockValue);
        rowSet.set(0, row);
        appService.storeFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, rowSet, recordId);

        return lockValue;
    }

    private String makeLockValue(String recordId, String username, int duration){
        Instant now = Instant.now().plusSeconds(1);
        Date lockUntilTime = addMinutesToDate(duration, Date.from(now));
        return getDateFormat().format(lockUntilTime) + ";" + username;
    }

    private String getLockUserFullname(String value) {
        ApplicationContext appContext = AppUtil.getApplicationContext();
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) appContext.getBean("directoryManager");
        User lockUser = directoryManager.getUserByUsername(getLockUsername(value));
        return getPropertyString("displayNameFormat").equalsIgnoreCase("firstLast") 
                            ? lockUser.getFirstName() + " " + lockUser.getLastName()
                            : lockUser.getLastName() + " " + lockUser.getFirstName();
    }

    private Element getSettings (AppDefinition appDef, String formDefId) {
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormDefinition formDef = formDefinitionDao.loadById(formDefId, appDef);
        return getElementByClassName(new JSONObject(formDef.getJson()), FormRecordLockingField.class.getName());
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
    
    private int durationToSeconds(String input) {
        int minutes = 0;
        int seconds = 0;

        String regex = "(\\d+)\\s*m|\\s*(\\d+)\\s*s";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                minutes = Integer.parseInt(matcher.group(1));
            }
            if (matcher.group(2) != null) {
                seconds = Integer.parseInt(matcher.group(2));
            }
        }

        return (minutes * 60) + seconds;
    }

    private FormRowSet getLockedRowSet(AppService appService, AppDefinition appDef, String formDefId, String recordId) {
        return appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, recordId);
    }

    private String getLockedValue(FormRowSet rowSet, Element settings) {
        String lockValue = "";
        if (!rowSet.isEmpty()) {
            lockValue = rowSet.get(0).getProperty(settings.getPropertyString("id"));
        }
        return lockValue;
    }
}