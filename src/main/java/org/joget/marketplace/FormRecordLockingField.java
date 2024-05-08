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

public class FormRecordLockingField extends Element implements FormBuilderPaletteElement {

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
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) appContext.getBean("directoryManager");
        
        final String primaryKey = formData.getPrimaryKeyValue();
        
        if (primaryKey != null && !primaryKey.isEmpty()) {
            LogUtil.debug(getClassName(), "FormRecordLockingField: Record - " + primaryKey);

            final String value = FormUtil.getElementPropertyValue(this, formData); //obtain lock record
            final String currentUsername = workflowUserManager.getCurrentUsername();
            
            //form load
            if (!isLockActive(value)) {
                //no lock is active
                if (!currentUsername.equalsIgnoreCase("roleAnonymous")) {
                    int lockDuration = Integer.parseInt(getPropertyString("lockDuration"));
                    setLock(primaryKey, currentUsername, lockDuration, getPropertyString("formDefId"));

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
    
    public void setLock(final String recordId, String username, int duration, String formDefId){
        Date lockUntilTime = addMinutesToDate(duration, new Date());
        
        final String value = getDateFormat().format(lockUntilTime) + ";" + username;
        
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
}
