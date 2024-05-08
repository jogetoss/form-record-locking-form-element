package org.joget.marketplace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
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
import org.joget.plugin.enterprise.JdbcLoadBinder;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.springframework.context.ApplicationContext;

public class FormRecordLockingValidator extends FormValidator {
    
    @Override
    public String getName() {
        return "Form Record Locking Validator";
    }

    @Override
    public String getVersion() {
        return "8.0.0";
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
        
        return AppUtil.readPluginResource(getClass().getName(), "/properties/form/formRecordLockingValidator.json", new Object[]{formDefField}, true, "messages/form/formRecordLockingValidator");   
    }

    @Override
    public String getElementDecoration() {
        return "";
    }

    @Override
    public boolean validate(Element element, FormData data, String[] values) {
        //form submission
        //prevent submssion if the lock is not valid
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        AppService appService = (AppService) appContext.getBean("appService");
        //String errorMessage = (String) getProperty("message");
        
        final String recordId = data.getPrimaryKeyValue();
        final String fieldId = FormUtil.getElementParameterName(element);
        final String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), getPropertyString("formDefId"));
        
        boolean updateRecordAllowed = updateRecordAllowed(recordId, fieldId, tableName, workflowUserManager.getCurrentUsername());
        //if lock is active
        if (!updateRecordAllowed) {
            //return error to prevent saving / concurrent edit / cross saving
            data.addFormError(FormUtil.getElementParameterName(element), getPropertyString("errorMessage"));
        }
        return updateRecordAllowed;
    }
    
    public boolean updateRecordAllowed(String recordId, String fieldId, String tableName, String currentUsername) {        
        if (recordId != null && !recordId.isEmpty()) {
            //Load the original Form Data record
            Map propertiesMap = new HashMap();
            propertiesMap.put("jdbcDatasource", "default");
            propertiesMap.put("sql", "SELECT c_" + fieldId + " FROM app_fd_" + tableName + " WHERE id = '" + recordId + "'");
            JdbcLoadBinder jdbcLoadBinder = new JdbcLoadBinder();
            jdbcLoadBinder.setProperties(propertiesMap);
            FormRowSet rowSet = jdbcLoadBinder.load(null, null, null);
            
            FormRow row = !rowSet.isEmpty() ? rowSet.get(0) : new FormRow();
            
            final String value = (String) row.get(fieldId); //get lock value
            
            try {
                String timestamp = value.split(";")[0];
                String username = value.split(";")[1];
                
                TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
                Date lockDateExpiry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timestamp);
                
                boolean lockDateExpiryActive = lockDateExpiry.after(new Date());
                LogUtil.debug(getClassName(), "FormRecordLockingFieldValidator: Lock State Active Until - " + lockDateExpiryActive + " GMT - By " + username);

                if (lockDateExpiryActive && !currentUsername.equalsIgnoreCase(username)) {
                    //lock is active, check for user match
                    //user NOT matches, cannot edit
                    LogUtil.debug(getClassName(), "FormRecordLockingFieldValidator: Update Denied To : " + currentUsername);
                    return false;
                }
            } catch(Exception ex) {
                //unable to determine, assume no lock is active, can update
                LogUtil.debug(getClassName(), "FormRecordLockingFieldValidator: Lock State Active : None Found");
                return true;
            }
        }
        
        return true; //no lock is in place
    }
}
