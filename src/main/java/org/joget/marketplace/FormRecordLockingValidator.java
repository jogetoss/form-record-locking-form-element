package org.joget.marketplace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.model.FormValidator;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.ResourceBundleUtil;
import org.joget.commons.util.StringUtil;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.springframework.context.ApplicationContext;

public class FormRecordLockingValidator extends FormValidator {

    @Override
    public String getName() {
        return "Form Record Locking Validator";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Form Record Locking Validator";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getLabel() {
        return getName();
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
        
        return AppUtil.readPluginResource(getClass().getName(), "/properties/form/formRecordLockingValidator.json", arguments, true, "messages/form/formRecordLockingValidator");   
    }
//    
//    @Override
//    public String getPropertyOptions() {
//        return "";
//        //return AppUtil.readPluginResource(getClass().getName(), "/properties/form/defaultValidator.json", null, true, "message/form/DefaultValidator");
//    }

    @Override
    public String getElementDecoration() {
        String decoration = "";
        
        return decoration;
    }

    @Override
    public boolean validate(Element element, FormData data, String[] values) {
        //form submission
        
        //prevent submssion if the lock is not valid
        
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        String currentUsername = workflowUserManager.getCurrentUsername();
        
        
        boolean result = true;
        String fieldId = FormUtil.getElementParameterName(element);
        //String errorMessage = (String) getProperty("message");
        String formDefId = (String) getProperty("formDefId");

        result = validateFormRecordLock(data, fieldId, "", formDefId, currentUsername);
        
        
        return result;
    }

    protected boolean validateFormRecordLock(FormData data, String fieldId, String message, String formDefId, String currentUsername) {   
        boolean updateRecordAllowed = updateRecordAllowed(data.getPrimaryKeyValue(), fieldId, formDefId, currentUsername);
        //if lock is active
        if(!updateRecordAllowed){
            //return error to prevent saving / concurrent edit / cross saving
            data.addFormError(fieldId, (String)getProperty("errorMessage"));
        }
        return updateRecordAllowed;
    }
    
    public boolean updateRecordAllowed(String recordId, String fieldId, String formDefId, String currentUsername){
        boolean updateRecordResult = true;
        
        if(recordId != null && !recordId.isEmpty()){

            //Load the original Form Data record
            AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
            AppDefinition appDef = AppUtil.getCurrentAppDefinition();

            FormRow row = new FormRow();
            FormRowSet rowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, recordId);
            if (!rowSet.isEmpty()) {
                row = rowSet.get(0);
            }
            
            String value = (String)row.get(fieldId); //get lock value
            
            try{
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];
                
                TimeZone.setDefault( TimeZone.getTimeZone("GMT"));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date lockDateExpiry = sdf.parse(timestamp);
                
                boolean lockDateExpiryActive = lockDateExpiry.after(new Date());
                LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingFieldValidator: Lock State Active Until - " + lockDateExpiryActive + " GMT - By " + username);

                if(lockDateExpiryActive){
                    //lock is active, check for user match
                    if(!currentUsername.equalsIgnoreCase(username)){
                        //user NOT matches, cannot edit
                        LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingFieldValidator: Update Denied To : " + currentUsername);
                        updateRecordResult = false;
                    }
                }
                return updateRecordResult;
            }catch(Exception ex){
                //unable to determine, assume no lock is active, can update
                
                LogUtil.debug(FormRecordLockingField.class.getName(), "FormRecordLockingFieldValidator: Lock State Active : None Found");
                return updateRecordResult;
            }
        }else{
            return updateRecordResult; //no lock is in place
        }
    }

}
