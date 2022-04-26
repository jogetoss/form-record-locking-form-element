package org.joget.marketplace;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.joget.apps.app.model.AppDefinition;
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
import org.joget.plugin.enterprise.FormDataUpdateTool ;
import org.joget.workflow.model.WorkflowAssignment;

public class FormRecordLockingField extends Element implements FormBuilderPaletteElement {

    @Override
    public String getName() {
        return "Form Record Locking";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Form Record Locking Field Element to prevent concurrent edit";
    }

    @Override
    public String renderTemplate(FormData formData, Map dataModel) {
        
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        String currentUsername = workflowUserManager.getCurrentUsername();
        String formDefId = getPropertyString("formDefId");
        ExtDirectoryManager directoryManager = (ExtDirectoryManager) appContext.getBean("directoryManager");
        String displayNameFormat = (String)getProperty("displayNameFormat");
        
        if(formData.getPrimaryKeyValue() == null || formData.getPrimaryKeyValue().isEmpty()){
            
        }else{
            String value = FormUtil.getElementPropertyValue(this, formData); //obtain lock record

            LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingField: Record - " + formData.getPrimaryKeyValue());

            //form load
            if(!isLockActive(value)){
                //no lock is active
                if(!currentUsername.equalsIgnoreCase("roleAnonymous")){
                    int lockDuration = Integer.parseInt(getPropertyString("lockDuration"));
                    setLock(formData.getPrimaryKeyValue(), currentUsername, lockDuration, formDefId);

                    dataModel.put("recordLockNew", true);
                    dataModel.put("recordLockDuration", lockDuration);
                }
            }else{
                //lock is active
                if(getLockUsername(value).equalsIgnoreCase(currentUsername)){
                    //lock is owned by current user
                    dataModel.put("recordLockOwner", true);
                    //future TODO: allow renewal by same user?
                }else{
                    //lock is not owned by current user
                    dataModel.put("recordLockOwner", false);
                    dataModel.put("removeSaveButton", true);
                }
                
                User lockUser = directoryManager.getUserByUsername(getLockUsername(value));
                String userFullname = "";
                
                if(displayNameFormat.equalsIgnoreCase("firstLast")){
                    userFullname = lockUser.getFirstName() + " " + lockUser.getLastName();
                }else{
                    userFullname = lockUser.getLastName() + " " + lockUser.getFirstName();
                }
                
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
        
        String template = "formRecordLocking.ftl";

        // set value
//        String value = FormUtil.getElementPropertyValue(this, formData);
//        dataModel.put("value", value);

        String html = FormUtil.generateElementHtml(this, formData, template, dataModel);
        return html;
    }
    
    public String getLockExpiryDurationLeft(String value){
        if(value != null && !value.isEmpty()){
            try{
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];

                TimeZone.setDefault( TimeZone.getTimeZone("GMT"));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date lockDateExpiry = sdf.parse(timestamp);
                
                long diff = lockDateExpiry.getTime() - (new Date()).getTime();
                long diffSeconds = diff / 1000 % 60;
                long diffMinutes = diff / (60 * 1000) % 60;
                
                if(diffMinutes > 0)
                    return diffMinutes + " m " + diffSeconds + " s";
                else
                    return diffSeconds + " s";
                
            }catch(Exception ex){
                //unable to determine, assume no lock is active, return false
                return "";
            }
        }else{
            return ""; //no lock is in place
        }
    }
    
    public String getLockExpiry(String value){
        if(value != null && !value.isEmpty()){
            try{
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];

                return timestamp;
            }catch(Exception ex){
                //unable to determine, assume no lock is active, return false
                return "";
            }
        }else{
            return ""; //no lock is in place
        }
    }
    
    public String getLockUsername(String value){
        if(value != null && !value.isEmpty()){
            try{
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];

                return username;
            }catch(Exception ex){
                //unable to determine, assume no lock is active, return false
                return "";
            }
        }else{
            return ""; //no lock is in place
        }
    }
    
    public boolean isLockActive(String value){
        if(value != null && !value.isEmpty()){
            try{
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];
                
                TimeZone.setDefault( TimeZone.getTimeZone("GMT"));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date lockDateExpiry = sdf.parse(timestamp);
                
                boolean lockDateExpiryActive = lockDateExpiry.after(new Date());
                LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingField: Lock State Active : " + lockDateExpiryActive);

                return lockDateExpiryActive;
            }catch(Exception ex){
                //unable to determine, assume no lock is active, return false
                
                LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingField: Lock State Active : None Found");
                return false;
            }
        }else{
            return false; //no lock is in place
        }
    }
    
    public void setLock(final String recordId, String username, int duration, String formDefId){
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        
        TimeZone.setDefault( TimeZone.getTimeZone("GMT"));
        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");//dd/MM/yyyy
        Date now = new Date();
        now = addMinutesToDate(duration, now);
        
        String strDate = sdfDate.format(now);
        final String value = strDate + ";" + username;
        
        //TODO: update same form record
        FormDataUpdateTool fdu = new FormDataUpdateTool();
        
        Map propertiesMap = new HashMap();
        WorkflowAssignment ass = new WorkflowAssignment();
        ass.setProcessId(recordId);
        
        Object[] fields = new Object[]{
            new HashMap() {{
            put("field", "id");
            put("value", recordId);
        }}, new HashMap() {{
            put("field", getPropertyString("id"));
            put("value", value);
        }} };
        
        propertiesMap.put("workflowAssignment", ass);
        propertiesMap.put("formDefId", formDefId);
        propertiesMap.put("fields", fields);
        propertiesMap.put("appDef", appDef);
        fdu.execute(propertiesMap);
        
        LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingField: Record - " + recordId + " - Apply Lock : " + value + " - Duration (min)" + duration);

        //System.out.println(value);
    }
    
    public FormRowSet formatData(FormData formData) {
        //form save
        
        FormRowSet rowSet = null;

        // get value
        String id = getPropertyString(FormUtil.PROPERTY_ID);
        if (id != null) {
            String value = FormUtil.getElementPropertyValue(this, formData);
            if (value != null) {
                // set value into Properties and FormRowSet object
                FormRow result = new FormRow();
                result.setProperty(id, value);
                rowSet = new FormRowSet();
                rowSet.add(result);
            }
        }

        return rowSet;
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
        
        Object[] arguments = new Object[]{formDefField};
        
        return AppUtil.readPluginResource(getClass().getName(), "/properties/form/formRecordLocking.json", arguments, true, "messages/form/formRecordLocking");
        
        
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
        final long ONE_MINUTE_IN_MILLIS = 60000;//millisecs

        long curTimeInMs = beforeTime.getTime();
        Date afterAddingMins = new Date(curTimeInMs + (minutes * ONE_MINUTE_IN_MILLIS));
        return afterAddingMins;
    }
}
