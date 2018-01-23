/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 * REST notes
 * https://spring.io/understanding/REST
 * https://user-images.githubusercontent.com/3287006/32914301-b2fbf798-cada-11e7-9541-a2bee8454c2c.png
 
 * POST
    * HTTP.POST can be used when the client is sending data to the server and the server
    * will decide the URI for the newly created resource. The POST method is used 
    * to request that the origin server accept the entity enclosed in the request
    * as a new subordinate of the resource identified by the Request-URI 
    * in the Request-Line.

 * PUT
    * HTTP.PUT can be used when the client is sending data to the the server and
    * the client is determining the URI for the newly created resource. The PUT method 
    * requests that the enclosed entity be stored under the supplied Request-URI. If 
    * the Request-URI refers to an already existing resource, the enclosed entity
    * SHOULD be considered as a modified version of the one residing on the origin
    * server. If the Request-URI does not point to an existing resource, 
    * and that URI is capable of being defined as a new resource by the requesting 
    * user agent, the origin server can create the resource with that URI.
    * It is most-often utilized for update capabilities, PUT-ing to a known resource
    * URI with the request body containing the newly-updated representation of the 
    * original resource.

 * PATCH
    * HTTP.PATCH can be used when the client is sending one or more changes to be
    * applied by the the server. The PATCH method requests that a set of changes described 
    * in the request entity be applied to the resource identified by the Request-URI.
    * The set of changes is represented in a format called a patch document.
    * Submits a partial modification to a resource. If you only need to update one
    * field for the resource, you may want to use the PATCH method.
 * 
 
 */

/**
 * Web Annotation Protocol Notes
 * Annotations can be updated by using a PUT request to replace the entire state of the Annotation. 
 * Annotation Servers should support this method. Servers may also support using a PATCH request to update only the aspects of the Annotation that have changed, 
 * but that functionality is not specified in this document.
 */

package edu.slu.action;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import com.opensymphony.xwork2.ActionSupport;
import edu.slu.common.Constant;
import edu.slu.mongoEntity.AcceptedServer;
import edu.slu.service.MongoDBService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.struts2.interceptor.ServletRequestAware;
import org.apache.struts2.interceptor.ServletResponseAware;
import org.bson.types.ObjectId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;


/**
 * @author hanyan &&  bhaberbe
 All the actions hit as an API like ex. /saveNewObject.action
 This implementation follows RESTFUL standards.  If you make changes, please adhere to this standard.

 */
public class ObjectAction extends ActionSupport implements ServletRequestAware, ServletResponseAware{
    private String content;
    private String oid;
    private AcceptedServer acceptedServer;
    private MongoDBService mongoDBService;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringBuilder bodyString;
    private BufferedReader bodyReader;
    private PrintWriter out;
    final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Check if the proposed object is a container type.
     * Related to Web Annotation compliance.  
     * @FIXME  We need to rethink what update.action does and how to separate and handle PUT vs PATCH gracefully and compliantly.
     * @param jo  the JSON or JSON-LD object
     * @see getAnnotationByObjectID(),saveNewObject(),updateObject() 
     * @return containerType Boolean representing if RERUM knows whether it is a container type or not.  
     */
    private Boolean isContainerType(JSONObject jo){
        Boolean containerType = false;
        String typestring;
        try{
            typestring = jo.getString("@type");
        }
        catch (Exception e){
            typestring = "";
        }
        //These are the types RERUM knows and IIIF says these types are containers.  How can we check against custom @context and types?
        if(typestring.equals("sc:Sequence") || typestring.equals("sc:AnnotationList") 
            || typestring.equals("sc:Range") || typestring.equals("sc:Layer")
            || typestring.equals("sc:Collection")){
            containerType = true;
        }
        return containerType; 
    }
    
    /**
     * Check if the proposed object is valid JSON-LD.
     * @param jo  the JSON object to check
     * @see getAnnotationByObjectID(),saveNewObject(),updateObject() 
     * @return isLd Boolean
     */
    public Boolean isLD(JSONObject jo){
        Boolean isLD=jo.containsKey("@context");
        return isLD;
        // TODO: There's probably some great code to do real checking.
    }
    
    /**
     * Write error to response.out.  The methods that call this function handle quitting, this just writes the error because of the quit. 
     * @param msg The message to show the user
     * @param status The HTTP response status to return
     */
    public void writeErrorResponse(String msg, int status){
        JSONObject jo = new JSONObject();
        jo.element("code", status);
        jo.element("message", msg);
        try {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.setContentType("application/json");
            response.setStatus(status);
            out = response.getWriter();
            out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
            out.write(System.getProperty("line.separator"));
        } 
        catch (IOException ex) {
            Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Add the __rerum properties object to a given JSONObject. If __rerum already exists, it will be overwritten because this method is only called on new objects.
     * Properties for consideration are:
     *   APIversion        —1.0.0
     *   history.prime     —if it has an @id, import from that, else "root"
     *   history.next      —always [] (or null, perhaps)
     *   history.previous  —if it has an @id, @id
     *   releases.previous —if it has an @id, import from that, else null
     *   releases.next     —always [] (or null, perhaps)
     *   generatedBy       —set to the @id of the public agent of the API Key.
     *   createdAt         —"addedTime" timestamp in milliseconds
     *   isOverwritten, isReleased   —always null
     * 
     * @param received A potentially optionless JSONObject from the Mongo Database (not the user).  This prevents tainted __rerum's
     * @return configuredObject The same object that was recieved but with the proper __rerum options.  This object is intended to be saved as a new object (@see versioning)
     */
    public JSONObject configureRerumOptions(JSONObject received, boolean update){
        JSONObject configuredObject = received;
        JSONObject received_options;
        try{
            //If this is an update, the object will have __rerum
            received_options = received.getJSONObject("__rerum"); 
        }
        catch(Exception e){ 
            //otherwise, it is a new save or an update on an object without the __rerum property
            received_options = new JSONObject();
        }
        JSONObject history = new JSONObject();
        JSONObject releases = new JSONObject();
        JSONObject rerumOptions = new JSONObject();
        String history_prime = "";
        String history_previous = "";
        String releases_previous = "";
        String releases_replaces = releases_previous;
        String[] emptyArray = new String[0];
        rerumOptions.element("APIversion", "1.0.0");
        rerumOptions.element("createdAt", System.currentTimeMillis());
        rerumOptions.element("isOverwritten", "");
        rerumOptions.element("isReleased", "");
        if(received_options.containsKey("history")){
            history = received_options.getJSONObject("history");
            if(update){
                //This means we are configuring from the update action and we have passed in a clone of the originating object (with its @id) that contained a __rerum.history
                if(history.getString("prime").equals("root")){
                    //Hitting this case means we are updating from the prime object, so we can't pass "root" on as the prime value
                    history_prime = received.getString("@id");
                }
                else{
                    //Hitting this means we are updating an object that already knows its prime, so we can pass on the prime value
                    history_prime = history.getString("prime");
                }
                //Either way, we know the previous value shold be the @id of the object received here. 
                history_previous = received.getString("@id");
            }
            else{
                //Hitting this means we are saving a new object and found that __rerum.history existed.  We don't trust it.
                history_prime = "root";
                history_previous = "";
            }
        }
        else{
            if(update){
             //Hitting this means we are updating an object that did not have __rerum history.  This is weird.  What should I do?
                //FIXME @cubap @theHabes
            }
            else{
             //Hitting this means we are are saving an object that did not have __rerum history.  This is normal   
                history_prime = "root";
                history_previous = "";
            }
        }
        if(received_options.containsKey("releases")){
            releases = received_options.getJSONObject("releases");
            releases_previous = releases.getString("previous");
        }
        else{
            releases_previous = "";         
        }
        releases.element("next", emptyArray);
        history.element("next", emptyArray);
        history.element("previous", history_previous);
        history.element("prime", history_prime);
        releases.element("previous", releases_previous);
        releases.element("replaces", releases_replaces);
        rerumOptions.element("history", history);
        rerumOptions.element("releases", releases);      
        //The access token is in the header  "Authorization: Bearer {YOUR_ACCESS_TOKEN}"
        //HttpResponse<String> response = Unirest.post("https://cubap.auth0.com/oauth/token") .header("content-type", "application/json") .body("{\"grant_type\":\"client_credentials\",\"client_id\": \"WSCfCWDNSZVRQrX09GUKnAX0QdItmCBI\",\"client_secret\": \"8Mk54OqMDqBzZgm7fJuR4rPA-4T8GGPsqLir2aP432NnmG6EAJBCDl_r_fxPJ4x5\",\"audience\": \"https://cubap.auth0.com/api/v2/\"}") .asString(); 
        rerumOptions.element("generatedBy",""); //TODO get the @id of the public agent of the API key
        configuredObject.element("__rerum", rerumOptions); //.element will replace the __rerum that is there OR create a new one
        return configuredObject; //The mongo save/update has not been called yet.  The object returned here will go into mongo.save or mongo.update
    }
    
    /**
     * Internal helper method to update the history.next property of an object.  This will occur because updateObject will create a new object from a given object, and that
     * given object will have a new next value of the new object.  Watch out for missing __rerum or malformed __rerum.history
     * 
     * @param idForUpdate the @id of the object whose history.next needs to be updated
     * @param newNextID the @id of the newly created object to be placed in the history.next array.
     * @return Boolean altered true on success, false on fail
     */
    private boolean alterHistoryNext (String idForUpdate, String newNextID){
        //TODO @theHabes As long as we trust the objects we send to this, we can take out the lookup and pass in objects as parameters
        Boolean altered = false;
        BasicDBObject query = new BasicDBObject();
        query.append("@id", idForUpdate);
        DBObject myAnno = mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query);
        DBObject myAnnoWithHistoryUpdate;
        JSONObject annoToUpdate = JSONObject.fromObject(myAnno);
        if(null != myAnno){
            try{
                annoToUpdate.getJSONObject("__rerum").getJSONObject("history").getJSONArray("next").add(newNextID); //write back to the anno from mongo
                myAnnoWithHistoryUpdate = (DBObject)JSON.parse(annoToUpdate.toString()); //make the JSONObject a DB object
                mongoDBService.update(Constant.COLLECTION_ANNOTATION, myAnno, myAnnoWithHistoryUpdate); //update in mongo
                altered = true;
            }
            catch(Exception e){ 
                //@cubap @theHabes #44.  What if obj does not have __rerum or __rerum.history
                writeErrorResponse("This object does not contain the proper history property.  It may not be from RERUM, the update failed.", HttpServletResponse.SC_CONFLICT);
            }
        }
        else{ //THIS IS A 404
            writeErrorResponse("Object for update not found...", HttpServletResponse.SC_NOT_FOUND);
        }
        return altered;
    }
    
    /**
     * Checks for appropriate RESTful method being used.
     * The action first comes to this function.  It says what type of request it 
     * is and checks the the method is appropriately RESTful.  Returns false if not and
     * the method that calls this will handle a false response;
     * @param http_request the actual http request object
     * @param request_type a string denoting what type of request this should be
     * @return Boolean indicating RESTfulness
     * @throws Exception 
    */
    public Boolean methodApproval(HttpServletRequest http_request, String request_type) throws Exception{
        String requestMethod = http_request.getMethod();
        boolean restful = false;
        // FIXME @webanno if you notice, OPTIONS is not supported here and MUST be 
        // for Web Annotation standards compliance.  
        switch(request_type){
            case "put_update":
                if(requestMethod.equals("PUT")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for updating, please use PUT to replace this object.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            case "patch_update":
                if(requestMethod.equals("PATCH")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for updating, please use PATCH to alter this RERUM object.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            case "patch_set":
                if(requestMethod.equals("PATCH")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for updating, PATCH to add or remove keys from this RERUM object.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            case "release":
            if(requestMethod.equals("PATCH")){
                restful = true;
            }
            else{
                writeErrorResponse("Improper request method for updating, please use PATCH to alter this RERUM object.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            break;
            case "create":
                if(requestMethod.equals("POST")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for creating, please use POST.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            case "delete":
                if(requestMethod.equals("DELETE")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for deleting, please use DELETE.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            case "get":
                if(requestMethod.equals("GET") || requestMethod.equals("HEAD")){
                    restful = true;
                }
                else{
                    writeErrorResponse("Improper request method for reading, please use GET or receive headers with HEAD.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            break;
            default:
                writeErrorResponse("Improper request method for this type of request (unknown).", HttpServletResponse.SC_METHOD_NOT_ALLOWED);

            }  
        return restful;
    }
    
    /**
     * All actions come here to process the request body. We check if it is JSON.
     * DELETE is a special case because the content could be JSON or just the @id string and it only has to specify a content type if passing a JSONy object.  
     * and pretty format it. Returns pretty stringified JSON or fail to null.
     * Methods that call this should handle requestBody==null as unexpected.
     * @param http_request Incoming request to check.
     * @param supportStringID The request may be allowed to pass the @id as the body.
     * @return String of anticipated JSON format.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     * @throws java.lang.Exception
     */
    public String processRequestBody(HttpServletRequest http_request, boolean supportStringID) throws IOException, ServletException, Exception{
        String cType = http_request.getContentType();
        String requestBody;
        JSONObject complianceInfo = new JSONObject();
        bodyReader = http_request.getReader();
        bodyString = new StringBuilder();
        String line;
        JSONObject test;
        JSONArray test2;
        if(cType.contains("application/json") || cType.contains("application/ld+json")){
            while ((line = bodyReader.readLine()) != null)
            {
              bodyString.append(line);
            }
            requestBody = bodyString.toString();
            try{ 
              //JSONObject test
              test = JSONObject.fromObject(requestBody);
            }
            catch(Exception ex){
                if(supportStringID){
                    //We do not allow arrays of ID's for DELETE, so if it failed JSONObject parsing then this is a hard fail for DELETE.
                    //They attempted to provide a JSON object for DELETE but it was not valid JSON
                    writeErrorResponse("The data passed was not valid JSON.  Could not get @id: "+requestBody, HttpServletResponse.SC_BAD_REQUEST);
                    requestBody = null;
                }
                else{
                    //Maybe it was an action on a JSONArray, check that before failing JSON parse test.
                    try{
                        //JSONArray test
                        test2 = JSONArray.fromObject(requestBody);
                    }
                    catch(Exception ex2){
                        // not a JSONObject or a JSONArray. 
                        writeErrorResponse("The data passed was not valid JSON:\n"+requestBody, HttpServletResponse.SC_BAD_REQUEST);
                        requestBody = null;
                    }
                }
            }          
            // no-catch: Is either JSONObject or JSON Array
        }
        else{ 
            if(supportStringID){ //Content type is not JSONy, looking for @id string as body
                while ((line = bodyReader.readLine()) != null)
                {
                  bodyString.append(line);
                }
                requestBody = bodyString.toString(); 
                try{
                    test=JSONObject.fromObject(requestBody);
                    if(test.containsKey("@id")){
                        requestBody = test.getString("@id");
                        if("".equals(requestBody)){
                        //No ID provided
                            writeErrorResponse("Must provide an id or a JSON object containing @id of object to perform this action.", HttpServletResponse.SC_BAD_REQUEST);
                            requestBody = null;
                        }
                        else{
                            // This string could be ANYTHING.  ANY string is valid at this point.  Create a wrapper JSONObject for elegant handling in deleteObject().  
                            // We will check against the string for existing objects in deleteObject(), processing the body is completed as far as this method is concerned.
                            JSONObject modifiedDeleteRequest = new JSONObject();
                            modifiedDeleteRequest.element("@id", requestBody);
                            requestBody = modifiedDeleteRequest.toString();
                        }
                    }
                }
                catch (Exception e){
                    //This is good, they should not be using a JSONObject
                    if("".equals(requestBody)){
                        //No ID provided
                        writeErrorResponse("Must provide an id or a JSON object containing @id of object to delete.", HttpServletResponse.SC_BAD_REQUEST);
                        requestBody = null;
                    }
                    else{
                        // This string could be ANYTHING.  ANY string is valid at this point.  Create a wrapper JSONObject for elegant handling in deleteObject().  
                        // We will check against the string for existing objects in deleteObject(), processing the body is completed as far as this method is concerned.
                        JSONObject modifiedDeleteRequest = new JSONObject();
                        modifiedDeleteRequest.element("@id", requestBody);
                        requestBody = modifiedDeleteRequest.toString();
                    }
                }
            }
            else{ //This is an error, actions must use the correct content type
                writeErrorResponse("Invalid Content-Type. Please use 'application/json' or 'application/ld+json'", HttpServletResponse.SC_BAD_REQUEST);
                requestBody = null;
            }
        }
        //@cubap @theHabes TODO IIIF compliance handling on action objects
        /*
        if(null != requestBody){
            complianceInfo = checkIIIFCompliance(requestBody, "2.1");
            if(complianceInfo.getInt("okay") < 1){
                writeErrorResponse(complianceInfo.toString(), HttpServletResponse.SC_CONFLICT);
                requestBody = null;
            }
        }
        */
        content = requestBody;
        response.setContentType("application/json"); // We create JSON objects for the return body in most cases.  
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        response.addHeader("Access-Control-Allow-Methods", "GET,OPTIONS,HEAD,PUT,PATCH,DELETE,POST"); // Must have OPTIONS for @webanno 
        return requestBody;
    }
    
    /**
     * Creates and appends headers to the HTTP response required by Web Annotation standards.
     * Headers are attached and read from {@link #response}. 
     * 
     * @param etag A unique fingerprint for the object for the Etag header.
     * @param isContainerType A boolean noting whether or not the object is a container type.
     * @param isLD  the object is either plain JSON or is JSON-LD ("ld+json")
     */
    private void addWebAnnotationHeaders(String etag, Boolean isContainerType, Boolean isLD){
        if(isLD){
            response.addHeader("Content-Type", "application/ld+json;profile=\"http://www.w3.org/ns/anno.jsonld\""); 
        } 
        else {
            response.addHeader("Content-Type", "application/json;"); 
            // This breaks Web Annotation compliance, but allows us to return requested
            // objects without misrepresenting the content.
        }
        if(isContainerType){
            response.addHeader("Link", "<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\""); 
            response.addHeader("Link", "<http://www.w3.org/TR/annotation-protocol/>; rel=\"http://www.w3.org/ns/ldp#constrainedBy\"");  
        }
        else{
            response.addHeader("Link", "<http://www.w3.org/ns/ldp#Resource>; rel=\"type\""); 
        }
        response.addHeader("Allow", "GET,OPTIONS,HEAD,PUT,PATCH,DELETE,POST"); 
        if(!"".equals(etag)){
            response.addHeader("Etag", etag);
        }
    }
    
    /**
     * Creates or appends Location header from @id.
     * Headers are attached and read from {@link #response}. 
     * @param obj  the JSON being returned to client
     * @see #addLocationHeader(net.sf.json.JSONArray) addLocationHeader(JSONArray)
     */
    private void addLocationHeader(JSONObject obj){
        String addLocation;
        // Warning: if there are multiple "Location" headers only one will be returned
        // our practice of making a list protects from this.
        if (response.containsHeader("Location")) {
            // add to existing header
            addLocation = response.getHeader("Location").concat(",").concat(obj.getString("@id"));
        }
        else {
            // no header attached yet
            addLocation = obj.getString("@id");
        }
        response.setHeader("Location", addLocation);
    }

    /**
     * Creates or appends list of @ids to Location header.
     * Headers are attached and read from {@link #response}. 
     * @param arr  the JSON Array being returned to the client
     * @see #addLocationHeader(net.sf.json.JSONObject) addLocationHeader(JSONObject)
     */    
    private void addLocationHeader(JSONArray arr){
        for(int j=0; j<arr.size(); j++){
            addLocationHeader(arr.getJSONObject(j)); 
        }
    }

    /** 
        * TODO @see batchSaveMetadataForm.  Do both methods need to exist?  Combine if possible. This is the method we use for generic bulk saving.
        * Each canvas has an annotation list with 0 - infinity annotations.  A copy requires a new annotation list with the copied annotations and a new @id.
        * Mongo allows us to bulk save.  
        * The content is from an HTTP request posting in an array filled with annotations to copy.  
     * @throws java.io.UnsupportedEncodingException
     * @throws javax.servlet.ServletException
        * @see MongoDBAbstractDAO.bulkSaveFromCopy(String collectionName, BasicDBList entity_array);
        * @see MongoDBAbstractDAO.bulkSetIDProperty(String collectionName, BasicDBObject[] entity_array);
    */ 
    public void batchSaveFromCopy() throws UnsupportedEncodingException, IOException, ServletException, Exception{
        if(null != processRequestBody(request, false) && methodApproval(request, "create")){
            JSONArray received_array = JSONArray.fromObject(content);
            for(int b=0; b<received_array.size(); b++){ //Configure __rerum on each object
                JSONObject configureMe = received_array.getJSONObject(b);
                configureMe = configureRerumOptions(configureMe, false); //configure this object
                received_array.set(b, configureMe); //Replace the current iterated object in the array with the configured object
            }
            BasicDBList dbo = (BasicDBList) JSON.parse(received_array.toString()); //tricky cause can't use JSONArray here
            JSONArray newResources = new JSONArray();
            //if the size is 0, no need to bulk save.  Nothing is there.
            if(dbo.size() > 0){
                newResources = mongoDBService.bulkSaveFromCopy(Constant.COLLECTION_ANNOTATION, dbo);
            }
            else {
                // empty array
            }
            //bulk save will automatically call bulk update 
            JSONObject jo = new JSONObject();
            jo.element("code", HttpServletResponse.SC_CREATED);
            jo.element("new_resources", newResources);
            addLocationHeader(newResources);
            try {
                out = response.getWriter();
                response.setStatus(HttpServletResponse.SC_CREATED);
                out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
            } catch (IOException ex) {
                Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    /**
     * Public facing servlet action to find all upstream versions of an object.  This is the action the user hits with the API.
     * If this object is `prime`, it will be the only object in the array.
     * @param oid variable assigned by urlrewrite rule for /id in urlrewrite.xml
     * @respond JSONArray to the response out for parsing by the client application.
     * @throws Exception 
     */
    public void getAllAncestors() throws Exception{
        if(null != oid && methodApproval(request, "get")){
            BasicDBObject query = new BasicDBObject();
            query.append("_id", oid);
            BasicDBObject mongo_obj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query);
            if(null != mongo_obj){
                JSONObject safe_received = JSONObject.fromObject(mongo_obj); //We can trust this is the object as it exists in mongo
                List<DBObject> ls_versions = getAllVersions(safe_received);
                JSONArray ancestors = getAllAncestors(ls_versions, safe_received, new JSONArray());
                try {
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    response.setStatus(HttpServletResponse.SC_OK);
                    out = response.getWriter();
                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(ancestors));
                } 
                catch (IOException ex) {
                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            else{
                writeErrorResponse("No object found with provided id '"+oid+"'.", HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
    
    /**
     * Internal method to filter ancestors upstream from `key object` until `root`. It should always receive a reliable object, not one from the user.
     * This list WILL NOT contains the keyObj.
     * 
     *  "Get requests can't have body"
     *  In fact in the standard they can (at least nothing says they can't). But lot of servers and firewall implementation suppose they can't 
     *  and drop them so using body in get request is a very bad idea.
     * 
     * @param ls_versions all the versions of the key object on all branches
     * @param keyObj The object from which to start looking for ancestors.  It is not included in the return. 
     * @param discoveredAncestors The array storing the ancestor objects discovered by the recursion.
     * @return array of objects
     */
    private JSONArray getAllAncestors(List<DBObject> ls_versions, JSONObject keyObj, JSONArray discoveredAncestors) {
        String previousID = keyObj.getJSONObject("__rerum").getJSONObject("history").getString("previous"); //The first previous to look for
        String rootCheck = keyObj.getJSONObject("__rerum").getJSONObject("history").getString("prime"); //Make sure the keyObj is not root.
        //@cubap @theHabes #44.  What if obj does not have __rerum or __rerum.history
        for (DBObject thisVersion : ls_versions) {
            JSONObject thisObject = JSONObject.fromObject(thisVersion);      
            if("root".equals(rootCheck)){
                //Check if we found root when we got the last object out of the list.  If so, we are done.  If keyObj was root, it will be detected here.  Break out. 
                break;
            }
            else if(thisObject.getString("@id").equals(previousID)){
                //If this object's @id is equal to the previous from the last object we found, its the one we want.  Look to its previous to keep building the ancestors Array.   
                previousID = thisObject.getJSONObject("__rerum").getJSONObject("history").getString("previous");
                rootCheck = thisObject.getJSONObject("__rerum").getJSONObject("history").getString("prime");
                if("".equals(previousID) && !"root".equals(rootCheck)){
                    //previous is blank and this object is not the root.  This is gunna trip it up.  
                    //@cubap Yikes this is a problem.  This branch on the tree is broken...what should we tell the user?  How should we handle?
                    
                    break;
                }
                else{
                    discoveredAncestors.add(thisObject);
                    //Recurse with what you have discovered so far and this object as the new keyObj
                    getAllAncestors(ls_versions, thisObject, discoveredAncestors);
                    break;
                }
            }                  
        }
        return discoveredAncestors;
    }
    
    /**
     * Public facing servlet to gather for all versions downstream from a provided `key object`.
     * @param oid variable assigned by urlrewrite rule for /id in urlrewrite.xml
     * @throws java.lang.Exception
     * @respond JSONArray to the response out for parsing by the client application.
     */
    public void getAllDescendents() throws Exception {
       if(null != oid && methodApproval(request, "get")){
            BasicDBObject query = new BasicDBObject();
            query.append("_id", oid);
            BasicDBObject mongo_obj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query);
            if(null != mongo_obj){
                JSONObject safe_received = JSONObject.fromObject(mongo_obj); //We can trust this is the object as it exists in mongo
                List<DBObject> ls_versions = getAllVersions(safe_received);
                JSONArray descendants = getAllDescendents(ls_versions, safe_received, new JSONArray());
                try {
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    response.setStatus(HttpServletResponse.SC_OK);
                    out = response.getWriter();
                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(descendants));
                } 
                catch (IOException ex) {
                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            else{
                writeErrorResponse("No object found with provided id '"+oid+"'.", HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
    
    /**
     * Internal method to find all downstream versions of an object.  It should always receive a reliable object, not one from the user.
     * If this object is the last, the return will be an empty JSONArray.  The keyObj WILL NOT be a part of the array.  
     * @param  ls_versions All the given versions, including root, of a provided object.
     * @param  keyObj The provided object
     * @param  discoveredDescendants The array storing the descendants objects discovered by the recursion.
     * @return All the objects that were deemed descendants in a JSONArray
     */

    private JSONArray getAllDescendents(List<DBObject> ls_versions, JSONObject keyObj, JSONArray discoveredDescendants){
        JSONArray nextIDarr = new JSONArray();
        //@cubap @theHabes #44.  What if obj does not have __rerum or __rerum.history
        if(keyObj.getJSONObject("__rerum").getJSONObject("history").getJSONArray("next").isEmpty()){
            //essentially, do nothing.  This branch is done.
        }
        else{
            //The provided object has nexts, get them to add them to known descendants then check their descendants.
            nextIDarr = keyObj.getJSONObject("__rerum").getJSONObject("history").getJSONArray("next");
        }      
        for(int m=0; m<nextIDarr.size(); m++){ //For each id in the array
            String nextID = nextIDarr.getString(m);
            for (DBObject thisVersion : ls_versions) {
                JSONObject thisObject = JSONObject.fromObject(thisVersion);
                if(thisObject.getString("@id").equals(nextID)){ //If it is equal, add it to the known descendants
                    discoveredDescendants.add(thisObject);
                    //Recurse with what you have discovered so far and this object as the new keyObj
                    getAllDescendents(ls_versions, thisObject, discoveredDescendants);
                    break;
                }
            }
        }
        return discoveredDescendants;
    }
    
    /**
     * Internal private method to loads all derivative versions from the `root` object. It should always receive a reliable object, not one from the user.
     * Used to resolve the history tree for storing into memory.
     * @param  obj A JSONObject to find all versions of.  If it is root, make sure to prepend it to the result.  If it isn't root, query for root from the ID
     * found in prime using that result as a reliable root object. 
     * @return All versions from the store of the object in the request
     * @throws Exception 
     */
    private List<DBObject> getAllVersions(JSONObject obj) throws Exception {
        List<DBObject> ls_versions = null;
        BasicDBObject rootObj;
        BasicDBObject query = new BasicDBObject();
        BasicDBObject queryForRoot = new BasicDBObject();  
        String primeID;
        //@cubap @theHabes #44.  What if obj does not have __rerum or __rerum.history
        if(obj.getJSONObject("__rerum").getJSONObject("history").getString("prime").equals("root")){
            primeID = obj.getString("@id");
            //Get all objects whose prime is this things @id
            query.append("__rerum.history.prime", primeID);
            ls_versions = mongoDBService.findByExample(Constant.COLLECTION_ANNOTATION, query);
            for(int i=0 ; i<ls_versions.size(); i++){
                BasicDBObject version = (BasicDBObject)ls_versions.get(i);
                version.remove("_id");
                ls_versions.set(i, version);
            }
            rootObj = (BasicDBObject) JSON.parse(obj.toString()); 
            rootObj.remove("_id");
            //Prepend the rootObj we know about
            ls_versions.add(0, rootObj);
        }
        else{
            primeID = obj.getJSONObject("__rerum").getJSONObject("history").getString("prime");
            //Get all objects whose prime is equal to this ID
            query.append("__rerum.history.prime", primeID);
            ls_versions = mongoDBService.findByExample(Constant.COLLECTION_ANNOTATION, query);
            for(int i=0 ; i<ls_versions.size(); i++){
                BasicDBObject version = (BasicDBObject)ls_versions.get(i);
                version.remove("_id");
                ls_versions.set(i, version);
            }
            queryForRoot.append("@id", primeID);
            rootObj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, queryForRoot);
            //Prepend the rootObj whose ID we knew and we queried for
            rootObj.remove("_id");
            ls_versions.add(0, rootObj);
        }
        return ls_versions;
    }
        
        
    /**
     * Get annotation by objectiD.  Strip all unnecessary key:value pairs before returning.
     * @param oid variable assigned by urlrewrite rule for /id in urlrewrite.xml
     * @rspond with the new annotation ID in the Location header and the new object created in the body.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void getByID() throws IOException, ServletException, Exception{
        if(null != oid && methodApproval(request, "get")){
            //find one version by objectID
            BasicDBObject query = new BasicDBObject();
            query.append("_id", oid);
            DBObject myAnno = mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query);
            if(null != myAnno){
                BasicDBObject bdbo = (BasicDBObject) myAnno;
                JSONObject jo = JSONObject.fromObject(myAnno.toMap());
                //String idForHeader = jo.getString("_id");
                //The following are rerum properties that should be stripped.  They should be in __rerum.
                jo.remove("_id");
                jo.remove("addedTime");
                jo.remove("originalAnnoID");
                jo.remove("version");
                jo.remove("permission");
                jo.remove("forkFromID"); // retained for legacy v0 objects
                jo.remove("serverName");
                jo.remove("serverIP");
                // @context may not be here and shall not be added, but the response
                // will not be ld+json without it.
                try {
                    addWebAnnotationHeaders(oid, isContainerType(jo), isLD(jo));
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    response.setStatus(HttpServletResponse.SC_OK);
                    out = response.getWriter();
                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                } 
                catch (IOException ex){
                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            else{
                writeErrorResponse("No object found with provided id '"+oid+"'.", HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
    
    /**
     * Get annotations by given properties. 
     * @param Object with key:value pairs with conditions to match against.
     * @reutrn list of annotations that match the given conditions.
     */
    // This is not Web Annotation standard as the specifications states you respond with a single object, not a list.  Not sure what to do with these.
    // @cubap answer: I asked on oac-discuss and was told Web Annotation hasn't handled lists yet, so just be nice.
    public void getByProperties() throws IOException, ServletException, Exception{
        if(null != processRequestBody(request, false) && methodApproval(request, "get")){
            JSONObject received = JSONObject.fromObject(content);
            BasicDBObject query = new BasicDBObject();
            Set<String> set_received = received.keySet();
            for(String key : set_received){
                query.append(key, received.get(key));
            }
            List<DBObject> ls_result = mongoDBService.findByExample(Constant.COLLECTION_ANNOTATION, query);
            JSONArray ja = new JSONArray();
            for(DBObject dbo : ls_result){
                ja.add((BasicDBObject) dbo);
            }
            if(ls_result.size() > 0){
                try {
                    response.addHeader("Content-Type","application/json"); // not ld+json because it is an array
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    response.setStatus(HttpServletResponse.SC_OK);
                    out = response.getWriter();
                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(ja));
                } 
                catch (IOException ex) {
                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            else{
                writeErrorResponse("Object(s) not found using provided properties '"+received+"'.", HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
    
    /**
     * Save a new annotation provided by the user. 
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     * @respond with new @id in Location header and the new annotation in the body.
     */
    public void saveNewObject() throws IOException, ServletException, Exception{
        if(null != processRequestBody(request, false) && methodApproval(request, "create")){
            JSONObject received = JSONObject.fromObject(content);
            if(received.containsKey("@id")){
                writeErrorResponse("Object already contains an @id "+received.containsKey("@id")+".  Either remove this property for saving or if it is a REERUM object update instead.", HttpServletResponse.SC_BAD_REQUEST);
            }
            else{
                JSONObject iiif_validation_response = checkIIIFCompliance(received, true); //This boolean should be provided by the user somehow.  It is a intended-to-be-iiif flag
                configureRerumOptions(received, false);
                DBObject dbo = (DBObject) JSON.parse(received.toString());
                if(null!=request.getHeader("Slug")){
                    // Slug is the user suggested ID for the annotation. This could be a cool RERUM thing.
                    // cubap: if we want, we can just copy the Slug to @id, warning
                    // if there was some mismatch, since versions are fine with that.
                }
                String newObjectID = mongoDBService.save(Constant.COLLECTION_ANNOTATION, dbo);
                //set @id from _id and update the annotation
                BasicDBObject dboWithObjectID = new BasicDBObject((BasicDBObject)dbo);
                String uid = "http://devstore.rerum.io/rerumserver/id/"+newObjectID;

                dboWithObjectID.append("@id", uid);
                mongoDBService.update(Constant.COLLECTION_ANNOTATION, dbo, dboWithObjectID);
                JSONObject jo = new JSONObject();
                JSONObject newObjWithID = JSONObject.fromObject(dboWithObjectID);
                jo.element("code", HttpServletResponse.SC_CREATED);
                jo.element("@id", uid);
                jo.element("iiif_validation", iiif_validation_response);
                try {
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    addWebAnnotationHeaders(newObjectID, isContainerType(received), isLD(received));
                    addLocationHeader(newObjWithID);
                    response.setStatus(HttpServletResponse.SC_CREATED);
                    out = response.getWriter();
                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                } 
                catch (IOException ex) {
                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                }
            }          
        }
    }

    /**
     * Public facing servlet to PATCH set or unset values of an existing RERUM object.
     * @respond with state of new object in the body
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void patchSetUpdate()throws IOException, ServletException, Exception{
        Boolean historyNextUpdatePassed = false;
        if(null!= processRequestBody(request, true) && methodApproval(request, "patch_set")){
            BasicDBObject query = new BasicDBObject();
            JSONObject received = JSONObject.fromObject(content); 
            String updateHistoryNextID = received.getString("@id");
            query.append("@id", updateHistoryNextID);
            BasicDBObject originalObject = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); //The originalObject DB object
            BasicDBObject updatedObject = (BasicDBObject) originalObject.copy(); //A copy of the original, this will be saved as a new object.  Make all edits to this variable.
            boolean alreadyDeleted = checkIfDeleted(JSONObject.fromObject(originalObject));
            if(alreadyDeleted){
                writeErrorResponse("The object you are trying to update is deleted.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            else{
                if(null != originalObject){
                    boolean setExistingKey = false;
                    boolean unsetNonExistingKey = false;
                    Set<String> update_anno_keys = received.keySet();
                    int updateCount = 0;
                    //If the object already in the database contains the key found from the object recieved from the user, error out this is not a set. 
                    for(String key : update_anno_keys){
                        if(originalObject.containsKey(key)){
                            if(key.equals("@id") || key.equals("__rerum") || key.equals("objectID") || key.equals("_id") ){
                                // Ignore these in a PATCH.  DO NOT update, DO NOT count as an attempt to update
                            }
                            else{
                                if(null != received.get(key)){ //Found matching keys and value is not null
                                    setExistingKey = true;
                                    writeErrorResponse("Attempted to set '"+key+"' on the object, but '"+key+"' already existed.", HttpServletResponse.SC_BAD_REQUEST);
                                    break;
                                }  
                                else{ //Found matching keys and value is null, this is a remove
                                    updatedObject.remove(key);
                                    updateCount +=1;
                                }
                            }
                        }
                        else{ //keys did not match, this is a set. 
                            if(null == received.get(key)){
                                //Tried to set key:null
                                unsetNonExistingKey = true;
                                writeErrorResponse("Attempted to unset '"+key+"' from the object, but '"+key+"' was not in the object.", HttpServletResponse.SC_BAD_REQUEST);
                                break;
                            }
                            else{
                                updatedObject.append(key, received.get(key));
                                updateCount += 1;
                            }
                        }
                    }
                    if(!setExistingKey && !unsetNonExistingKey){
                        if(updateCount > 0){
                            JSONObject newObject = JSONObject.fromObject(updatedObject);//The edited original object meant to be saved as a new object (versioning)
                            newObject = configureRerumOptions(newObject, true); //__rerum for the new object being created because of the update action
                            newObject.remove("@id"); //This is being saved as a new object, so remove this @id for the new one to be set.
                            //Since we ignore changes to __rerum for existing objects, we do no configureRerumOptions(updatedObject);
                            DBObject dbo = (DBObject) JSON.parse(newObject.toString());
                            String newNextID = mongoDBService.save(Constant.COLLECTION_ANNOTATION, dbo);
                            String newNextAtID = "http://devstore.rerum.io/rerumserver/id/"+newNextID;
                            BasicDBObject dboWithObjectID = new BasicDBObject((BasicDBObject)dbo);
                            dboWithObjectID.append("@id", newNextAtID);
                            newObject.element("@id", newNextAtID);
                            newObject.remove("_id");
                            mongoDBService.update(Constant.COLLECTION_ANNOTATION, dbo, dboWithObjectID);
                            historyNextUpdatePassed = alterHistoryNext(updateHistoryNextID, newNextAtID); //update history.next or original object to include the newObject @id
                            if(historyNextUpdatePassed){
                                JSONObject jo = new JSONObject();
                                JSONObject iiif_validation_response = checkIIIFCompliance(newNextAtID, "2.1");
                                jo.element("code", HttpServletResponse.SC_OK);
                                jo.element("original_object_id", updateHistoryNextID);
                                jo.element("new_obj_state", newObject); //FIXME: @webanno standards say this should be the response.
                                jo.element("iiif_validation", iiif_validation_response);
                                try {
                                    addWebAnnotationHeaders(newNextID, isContainerType(newObject), isLD(newObject));
                                    response.addHeader("Access-Control-Allow-Origin", "*");
                                    response.setStatus(HttpServletResponse.SC_OK);
                                    out = response.getWriter();
                                    out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                                } 
                                catch (IOException ex) {
                                    Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                            else{
                                //The error is already written to response.out, do nothing.
                            }
                        }
                        else{
                            // Nothing could be patched
                            addLocationHeader(received);
                            writeErrorResponse("Nothing could be PATCHed", HttpServletResponse.SC_NO_CONTENT);
                        }
                    }
                }
                else{
                    //This could mean it was an external object, so we can save it as a new object (new object is root) and refer to this @id in previous.
                    //TODO FIXME @cubap @theHabes #41
                    writeErrorResponse("Object "+received.getString("@id")+" not found in RERUM, could not update.", HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        }
    }
    
    /**
     * Update a given annotation. Cannot set or unset keys.  
     * @respond with state of new object in the body
     */
    public void patchUpdateObject() throws ServletException, Exception{
        Boolean historyNextUpdatePassed = false;
        if(null!= processRequestBody(request, true) && methodApproval(request, "patch_update")){
            BasicDBObject query = new BasicDBObject();
            JSONObject received = JSONObject.fromObject(content); 
            String updateHistoryNextID = received.getString("@id");
            query.append("@id", updateHistoryNextID);
            BasicDBObject originalObject = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); //The originalObject DB object
            BasicDBObject updatedObject = (BasicDBObject) originalObject.copy(); //A copy of the original, this will be saved as a new object.  Make all edits to this variable.
            boolean alreadyDeleted = checkIfDeleted(JSONObject.fromObject(originalObject));
            if(alreadyDeleted){
                writeErrorResponse("The object you are trying to update is deleted.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            else{
                if(null != originalObject){
                    Set<String> update_anno_keys = received.keySet();
                    boolean triedToSet = false;
                    int updateCount = 0;
                    //If the object already in the database contains the key found from the object recieved from the user, update it barring a few special keys
                    //Users cannot update the __rerum property, so we ignore any update action to that particular field.  
                    for(String key : update_anno_keys){
                        if(originalObject.containsKey(key) ){
                            //Skip keys we want to ignore and keys that match but have matching values
                            if(!(key.equals("@id") || key.equals("__rerum") || key.equals("objectID") || key.equals("_id")) && received.get(key) != originalObject.get(key)){
                                updatedObject.remove(key);
                                updatedObject.append(key, received.get(key));
                                updateCount +=1 ;
                            }
                        }
                        else{
                            triedToSet = true;
                            break;
                        }
                    }
                    if(triedToSet){
                        writeErrorResponse("A key you are trying to update does not exist on the object.  You can set with the patch_set or put_update action.", HttpServletResponse.SC_BAD_REQUEST);
                    }
                    else if(updateCount == 0){
                        addLocationHeader(received);
                        writeErrorResponse("Nothing could be PATCHed", HttpServletResponse.SC_NO_CONTENT);
                    }
                    else{
                        JSONObject newObject = JSONObject.fromObject(updatedObject);//The edited original object meant to be saved as a new object (versioning)
                        newObject = configureRerumOptions(newObject, true); //__rerum for the new object being created because of the update action
                        newObject.remove("@id"); //This is being saved as a new object, so remove this @id for the new one to be set.
                        //Since we ignore changes to __rerum for existing objects, we do no configureRerumOptions(updatedObject);
                        DBObject dbo = (DBObject) JSON.parse(newObject.toString());
                        String newNextID = mongoDBService.save(Constant.COLLECTION_ANNOTATION, dbo);
                        String newNextAtID = "http://devstore.rerum.io/rerumserver/id/"+newNextID;
                        BasicDBObject dboWithObjectID = new BasicDBObject((BasicDBObject)dbo);
                        dboWithObjectID.append("@id", newNextAtID);
                        newObject.element("@id", newNextAtID);
                        mongoDBService.update(Constant.COLLECTION_ANNOTATION, dbo, dboWithObjectID);
                        historyNextUpdatePassed = alterHistoryNext(updateHistoryNextID, newNextAtID); //update history.next or original object to include the newObject @id
                        if(historyNextUpdatePassed){
                            JSONObject jo = new JSONObject();
                            JSONObject iiif_validation_response = checkIIIFCompliance(newNextAtID, "2.1");
                            jo.element("code", HttpServletResponse.SC_OK);
                            jo.element("original_object_id", updateHistoryNextID);
                            jo.element("new_obj_state", newObject); //FIXME: @webanno standards say this should be the response.
                            jo.element("iiif_validation", iiif_validation_response);
                            try {
                                addWebAnnotationHeaders(newNextID, isContainerType(newObject), isLD(newObject));
                                response.addHeader("Access-Control-Allow-Origin", "*");
                                response.setStatus(HttpServletResponse.SC_OK);
                                out = response.getWriter();
                                out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                            } 
                            catch (IOException ex) {
                                Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else{
                            //The error is already written to response.out, do nothing.
                        }
                    }
                }
                else{
                    //This could mean it was an external object, so we can save it as a new object (new object is root) and refer to this @id in previous.
                    //TODO FIXME @cubap @theHabes #41
                    writeErrorResponse("Object "+received.getString("@id")+" not found in RERUM, could not update.", HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        }
    }
    
     /**
     * Public facing servlet to release an existing RERUM object.  This will not perform history tree updates, but rather releases tree updates.
     * (AKA a new node in the history tree is NOT CREATED here.)
     * 
     * @respond with new state of the object in the body.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void releaseObject() throws IOException, ServletException, Exception{
        boolean treeHealed = false;
        System.out.println("releasing...");
        if(null!= processRequestBody(request, true) && methodApproval(request, "release")){
            BasicDBObject query = new BasicDBObject();
            JSONObject received = JSONObject.fromObject(content);
            System.out.println(received);
            if(received.containsKey("@id")){
                System.out.println("Found the @id");
                String updateToReleasedID = received.getString("@id");
                query.append("@id", updateToReleasedID);
                BasicDBObject originalObject = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); //The original DB object
                BasicDBObject releasedObject = (BasicDBObject) originalObject.copy(); //A copy of the original.  Make all edits to this variable.
                JSONObject safe_original = JSONObject.fromObject(originalObject); //The original object represented as a JSON object.  Safe for edits. 
                String previousReleasedID = safe_original.getJSONObject("__rerum").getJSONObject("releases").getString("previous");
                JSONArray nextReleases = safe_original.getJSONObject("__rerum").getJSONObject("releases").getJSONArray("next");
                boolean alreadyReleased = checkIfReleased(safe_original);
                if(alreadyReleased){
                    System.out.println("already released!");
                    writeErrorResponse("This object is already released.  You must fork this annotation as one of your own to release it.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                else{
                    if(null != originalObject){
                        System.out.println("It is a RERUM object");
                        safe_original.getJSONObject("__rerum").element("isReleased", System.currentTimeMillis()+"");
                        releasedObject = (BasicDBObject) JSON.parse(safe_original.toString());
                        if(!"".equals(previousReleasedID)){// A releases tree exists and an acestral object is being released.  
                            System.out.println("A releases tree exists and an acestral object is being released.  ");
                            treeHealed  = healReleasesTree(safe_original); 
                        }
                        else{ //There was no releases previous value. 
                            if(nextReleases.size() > 0){ //The release tree has been established and a descendent object is now being released.
                                System.out.println("The release tree has been established and a descendent object is now being released.");
                                treeHealed  = healReleasesTree(safe_original);
                            }
                            else{ //The release tree has not been established
                                System.out.println("The release tree has not been established");
                                treeHealed = establishReleasesTree(safe_original);
                            }
                        }
                        if(treeHealed){ //If the tree was established/healed
                            //perform the update to isReleased of the object being released.  Its releases.next[] and releases.previous are already correct.
                            System.out.println("perform the update to isReleased of the object being released");
                            mongoDBService.update(Constant.COLLECTION_ANNOTATION, originalObject, releasedObject);
                            JSONObject jo = new JSONObject();
                            jo.element("code", HttpServletResponse.SC_OK);
                            jo.element("new_obj_state", releasedObject); //FIXME: @webanno standards say this should be the response.
                            jo.element("previously_released_id", previousReleasedID); 
                            jo.element("next_releases_ids", nextReleases);
                            System.out.println("BYE!");
                            System.out.println(jo);
                            try {
                                addWebAnnotationHeaders(updateToReleasedID, isContainerType(safe_original), isLD(safe_original));
                                response.addHeader("Access-Control-Allow-Origin", "*");
                                response.setStatus(HttpServletResponse.SC_OK);
                                out = response.getWriter();
                                out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                            } 
                            catch (IOException ex) {
                                Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else{
                            //The error is already written to response.out, do nothing.
                        }
                    }
                    else{
                        //This could mean it was an external object, but the release action fails on those.
                        System.out.println("In RERUM");
                        writeErrorResponse("Object "+received.getString("@id")+" not found in RERUM, could not release.", HttpServletResponse.SC_BAD_REQUEST);
                    }
                }    
            }
            else{
                writeErrorResponse("Object did not contains an @id, could not release.", HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }
    
    /**
     * Internal helper method to update the releases tree from a given object that is being released.  
     * https://www.geeksforgeeks.org/find-whether-an-array-is-subset-of-another-array-set-1/
     * 
     * This method only receives reliable objects from mongo.
     * 
     * @param obj the RERUM object being released
     * @return Boolean success or some kind of Exception
     */
    private boolean healReleasesTree (JSONObject releasingNode) throws Exception{
        System.out.println("Heal Release Tree Helper!");
        Boolean success = true;
        List<DBObject> ls_versions = getAllVersions(releasingNode);
        JSONArray descendents = getAllDescendents(ls_versions, releasingNode, new JSONArray());
        JSONArray anscestors = getAllAncestors(ls_versions, releasingNode, new JSONArray());
        System.out.println("Got "+anscestors.size()+" ancestors and "+descendents.size()+" descedents");
        for(int d=0; d<descendents.size(); d++){ //For each descendent
            JSONObject desc = descendents.getJSONObject(d);
            boolean prevMatchCheck = desc.getJSONObject("__rerum").getJSONObject("releases").getString("previous").equals(releasingNode.getJSONObject("__rerum").getJSONObject("releases").getString("previous"));
            DBObject origDesc = (DBObject) JSON.parse(desc.toString());
            if(prevMatchCheck){ 
                System.out.println(desc.getJSONObject("__rerum").getJSONObject("releases").getString("previous")+" equals "+releasingNode.getJSONObject("__rerum").getJSONObject("releases").getString("previous"));
                System.out.println("the descendant's previous matches the node I am releasing's releases.previous");
                //If the descendant's previous matches the node I am releasing's releases.previous, replace descendant releses.previous with node I am releasing's @id. 
                desc.getJSONObject("__rerum").getJSONObject("releases").element("previous", releasingNode.getString("@id"));
                if(!desc.getJSONObject("__rerum").getString("isReleased").equals("")){ 
                    System.out.println("Found a replaces situation.  An descendent of an object I am releasing is released.");
                    //If this descendent is released, it replaces our node being released
                    desc.getJSONObject("__rerum").getJSONObject("releases").element("replaces", releasingNode.getString("@id")); 
                }
                DBObject descToUpdate = (DBObject) JSON.parse(desc.toString());
                mongoDBService.update(Constant.COLLECTION_ANNOTATION, origDesc, descToUpdate);
            }
        }
        JSONArray origNextArray = releasingNode.getJSONObject("__rerum").getJSONObject("releases").getJSONArray("next");
        for(int a=0; a<anscestors.size(); a++){ //For each ancestor
            JSONArray ancestorNextArray = anscestors.getJSONObject(a).getJSONObject("__rerum").getJSONObject("releases").getJSONArray("next");
            JSONObject ans = anscestors.getJSONObject(a);
            DBObject origAns = (DBObject) JSON.parse(ans.toString());
            if(origNextArray.size() == 0){ //The releases.next on the node I am releasing is empty.  All ancestors who are empty should have the releasing node's @id added to them.
                if(ancestorNextArray.size() == 0){
                    ancestorNextArray.add(releasingNode.getString("@id")); //Add the id of the node I am releasing into the ancestor's releases.next array.
                }
                System.out.println("Is ancestor released?  "+ans.getJSONObject("__rerum").getString("isReleased"));
                if(!ans.getJSONObject("__rerum").getString("isReleased").equals("")){ 
                    System.out.println("Found a replaces situation.  An ancestor of an object I am releasing is released 1");
                    //If this ancestor is released, our node being released replaces it
                    DBObject origReleaseNode = (DBObject) JSON.parse(releasingNode.toString());
                    releasingNode.getJSONObject("__rerum").getJSONObject("releases").element("replaces", ans.getString("@id"));
                    DBObject releaseNodeWithReplaces = (DBObject) JSON.parse(releasingNode.toString());
                    //Since this is an edit to the node being released, we have to update it in the store.  It will also be updated when this calls back to replaceObject(), but
                    //this function is not intended to return an object so we should update here instead of returning the object and updating there.  
                    mongoDBService.update(Constant.COLLECTION_ANNOTATION, origReleaseNode, releaseNodeWithReplaces);
                }
            }
            else{
                for (int i=0; i<origNextArray.size(); i++){ //For each id in the next array of the object I am releasing (it could be []).
                    String compareOrigNextID = origNextArray.getString(i);
                    for(int j=0; j<ancestorNextArray.size(); j++){ //For each id in the ancestor's releases.next array
                        String compareAncestorID = ancestorNextArray.getString(j);
                        if (compareOrigNextID.equals(compareAncestorID)){ //If the id is in the next array of the object I am releasing and in the releases.next array of the ancestor
                            System.out.println(compareOrigNextID +" equals "+compareAncestorID);
                            System.out.println("the id is in the next array of the object I am releasing and in the releases.next array of the ancestor. remove that id from the ancestor next array.");
                            ancestorNextArray.remove(j); //remove that id.
                        }
                        System.out.println("Is ancestor released?  "+ans.getJSONObject("__rerum").getString("isReleased"));
                        if(!ans.getJSONObject("__rerum").getString("isReleased").equals("")){ 
                            System.out.println("Found a replaces situation.  An ancestor of an object I am releasing is released 2");
                            //If this ancestor is released, our node being released replaces it
                            DBObject origReleaseNode = (DBObject) JSON.parse(releasingNode.toString());
                            releasingNode.getJSONObject("__rerum").getJSONObject("releases").element("replaces", ans.getString("@id"));
                            DBObject releaseNodeWithReplaces = (DBObject) JSON.parse(releasingNode.toString());
                            //Since this is an edit to the node being released, we have to update it in the store.  It will also be updated when this calls back to replaceObject(), but
                            //this function is not intended to return an object so we should update here instead of returning the object and updating there.  
                            mongoDBService.update(Constant.COLLECTION_ANNOTATION, origReleaseNode, releaseNodeWithReplaces);
                        }
                        if(j == ancestorNextArray.size()-1 ||ancestorNextArray.size() == 0 ){ //Once I have checked against all id's in the ancestor node releases.next[] and removed the ones I needed to
                            System.out.println("I have checked against all id's in the ancestor node releases.next[] and removed the ones I needed to");
                            System.out.println("Add the id of the node I am releasing into the ancestor's releases.next array.");
                            System.out.println("Add "+releasingNode.getString("@id")+" to "+ancestorNextArray);
                            ancestorNextArray.add(releasingNode.getString("@id")); //Add the id of the node I am releasing into the ancestor's releases.next array.
                        }
                    }
                } 
            }

            System.out.println("Update ancestral node releases.next to the new array ");
            System.out.println(ancestorNextArray);
            ans.getJSONObject("__rerum").getJSONObject("releases").element("next", ancestorNextArray);
            DBObject ansToUpdate = (DBObject) JSON.parse(ans.toString());
            mongoDBService.update(Constant.COLLECTION_ANNOTATION, origAns, ansToUpdate);
        }
        return success;
    } 
    
    /**
     * Internal helper method to establish the releases tree from a given object that is being released.  
     * This can probably be collapsed into healReleasesTree.  It contains no checks, it is brute force update ancestors and descendents.
     * It is significantly cleaner and slightly faster than healReleaseTree() which is why I think we should keep them separate. 
     *  
     * This method only receives reliable objects from mongo.
     * 
     * @param obj the RERUM object being released
     * @return Boolean sucess or some kind of Exception
     */
    private boolean establishReleasesTree (JSONObject obj) throws Exception{
        System.out.println("Establish Tree Helper!");
        Boolean success = true;
        List<DBObject> ls_versions = getAllVersions(obj);
        JSONArray descendents = getAllDescendents(ls_versions, obj, new JSONArray());
        JSONArray anscestors = getAllAncestors(ls_versions, obj, new JSONArray());
        System.out.println("Got ancestors and descendents.");
        for(int d=0; d<descendents.size(); d++){
            JSONObject desc = descendents.getJSONObject(d);
            DBObject origDesc = (DBObject) JSON.parse(desc.toString());
            desc.getJSONObject("__rerum").getJSONObject("releases").element("previous", obj.getString("@id"));
            DBObject descToUpdate = (DBObject) JSON.parse(desc.toString());
            mongoDBService.update(Constant.COLLECTION_ANNOTATION, origDesc, descToUpdate);
        }
        for(int a=0; a<anscestors.size(); a++){
            JSONObject ans = anscestors.getJSONObject(a);
            DBObject origAns = (DBObject) JSON.parse(ans.toString());
            ans.getJSONObject("__rerum").getJSONObject("releases").getJSONArray("next").add(obj.getString("@id"));
            DBObject ansToUpdate = (DBObject) JSON.parse(ans.toString());
            mongoDBService.update(Constant.COLLECTION_ANNOTATION, origAns, ansToUpdate);
        }
        System.out.println("Established the tree for "+anscestors.size()+" ancestors and "+descendents.size()+" descendents");
        return success;
    }
    
    /**
     * Public facing servlet to PUT replace an existing object.  Can set and unset keys.
     * @respond with new state of the object in the body.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void putUpdateObject()throws IOException, ServletException, Exception{
        //@webanno The client should use the If-Match header with a value of the ETag it received from the server before the editing process began, 
        //to avoid collisions of multiple users modifying the same Annotation at the same time
        //cubap: I'm not sold we have to do this. Our versioning would allow multiple changes. 
        //The application might want to throttle internally, but it can.
        Boolean historyNextUpdatePassed = false;
        System.out.println("PUT update");
        if(null!= processRequestBody(request, true) && methodApproval(request, "put_update")){
            System.out.println("PUT update 2");
            BasicDBObject query = new BasicDBObject();
            JSONObject received = JSONObject.fromObject(content); 
            String updateHistoryNextID = received.getString("@id");
            query.append("@id", updateHistoryNextID);
            BasicDBObject originalObject = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); //The originalObject DB object
            BasicDBObject updatedObject = (BasicDBObject) JSON.parse(received.toString()); //A copy of the original, this will be saved as a new object.  Make all edits to this variable.
            JSONObject originalJSONObj = JSONObject.fromObject(originalObject);
            boolean alreadyDeleted = checkIfDeleted(JSONObject.fromObject(originalObject));
            System.out.println("1");
            if(alreadyDeleted){
                writeErrorResponse("The object you are trying to update is deleted.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            else{
                System.out.println("2");
                if(null != originalObject){
                    System.out.println("3");
                    JSONObject newObject = JSONObject.fromObject(updatedObject);//The edited original object meant to be saved as a new object (versioning)
                    JSONObject originalProperties = originalJSONObj.getJSONObject("__rerum");
                    newObject.element("__rerum", originalProperties);
                    //Since this is a put update, it is possible __rerum is not in the object provided by the user.  We get a reliable copy oof the original out of mongo
                    newObject = configureRerumOptions(newObject, true); //__rerum for the new object being created because of the update action
                    newObject.remove("@id"); //This is being saved as a new object, so remove this @id for the new one to be set.
                    DBObject dbo = (DBObject) JSON.parse(newObject.toString());
                    String newNextID = mongoDBService.save(Constant.COLLECTION_ANNOTATION, dbo);
                    System.out.println("4");
                    String newNextAtID = "http://devstore.rerum.io/rerumserver/id/"+newNextID;
                    BasicDBObject dboWithObjectID = new BasicDBObject((BasicDBObject)dbo);
                    dboWithObjectID.append("@id", newNextAtID);
                    newObject.element("@id", newNextAtID);
                    mongoDBService.update(Constant.COLLECTION_ANNOTATION, dbo, dboWithObjectID);
                    historyNextUpdatePassed = alterHistoryNext(updateHistoryNextID, newNextAtID); //update history.next or original object to include the newObject @id
                    System.out.println("5");
                    if(historyNextUpdatePassed){
                        JSONObject jo = new JSONObject();
                        JSONObject iiif_validation_response = checkIIIFCompliance(newNextAtID, "2.1");
                        jo.element("code", HttpServletResponse.SC_OK);
                        jo.element("original_object_id", updateHistoryNextID);
                        jo.element("new_obj_state", newObject); //FIXME: @webanno standards say this should be the response.
                        jo.element("iiif_validation", iiif_validation_response);
                        try {
                            addWebAnnotationHeaders(newNextID, isContainerType(newObject), isLD(newObject));
                            response.addHeader("Access-Control-Allow-Origin", "*");
                            response.setStatus(HttpServletResponse.SC_OK);
                            out = response.getWriter();
                            out.write(mapper.writer().withDefaultPrettyPrinter().writeValueAsString(jo));
                        }
                        catch (IOException ex) {
                            Logger.getLogger(ObjectAction.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                    else{
                        //The error is already written to response.out, do nothing.
                    }
                }
                else{
                    //This could mean it was an external object, so we can save it as a new object (new object is root) and refer to this @id in previous.
                    //TODO FIXME @cubap @theHabes #41
                    writeErrorResponse("Object "+received.getString("@id")+" not found in RERUM, could not update.", HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        }
    }
    
    /**
     * A helper function that determines whether or not an object has been flagged as deleted.
     * This should only be fed reliable objects from mongo
     * @param obj
     * @return A boolean representing the truth.
     */
    private boolean checkIfDeleted(JSONObject obj){
        boolean deleted = obj.containsKey("__deleted");
        return deleted;
    }
    
    /**
     * A public facing servlet to determine if a given object ID is for a deleted object.
     * @FIXME make the parameter the http_request, get the @id, get from mongo and feed to private version.
     * @param obj_id
     * @return A boolean representing the truth.
     */
    public boolean checkIfDeleted(String obj_id){
        BasicDBObject query = new BasicDBObject();
        BasicDBObject dbObj;
        JSONObject checkThis = new JSONObject();
        query.append("@id", obj_id);
        dbObj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); 
        if(null != dbObj){
            checkThis = JSONObject.fromObject(dbObj);
        }
        
        return checkIfDeleted(checkThis);

    }
    
    /**
    * check that the API keys match and that this application has permission to delete the object. These are internal and the objects passed in are
     * first taken from mongo, they are not the obj provided by the application.
    */
    private boolean checkApplicationPermission(JSONObject obj){
        boolean permission = true;
        //@cubap @theHabes TODO check that the API keys match and that this application has permission to delete the object
        return permission;
    }
    
    /**
     * An internal helper function to check if an object is released.
     * This should only be fed reliable objects from mongo.
     * check that the API keys match and that this application has permission to delete the object. These are internal and the objects passed in are
     * first taken from mongo, they are not the obj provided by the application.
    */
    private boolean checkApplicationPermission(String obj_id){
        boolean permission = true;
        //@cubap @theHabes TODO check that the API keys match and that this application has permission to delete the object
        return permission;
    }
    
    /**
     * A helper function that gathers an object by its id and determines whether or not it is flagged as released. These are internal and the objects passed in are
     * first taken from mongo, they are not the obj provided by the application.
     * @param obj
     * @return 
     */
    private boolean checkIfReleased(JSONObject obj){
        boolean released = false;
        System.out.println("Object to check released is ");
        System.out.println(obj);
        //@cubap @theHabes #44.  What if obj does not have __rerum
        if(!obj.containsKey("__rerum") || !obj.getJSONObject("__rerum").containsKey("isReleased")){
            released = false;
        }
        else if(!obj.getJSONObject("__rerum").getString("isReleased").equals("")){
            released = true;
        }
        return released;
    }
    
    /**
     * Public facing servlet that checks whether the provided object id is of a released object.
     * @param obj_id
     * @return 
     */
    public boolean checkIfReleased(String obj_id){
        BasicDBObject query = new BasicDBObject();
        BasicDBObject dbObj;
        JSONObject checkThis = new JSONObject();
        query.append("@id", obj_id);
        dbObj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); 
        if(null != dbObj){
            checkThis = JSONObject.fromObject(dbObj);
        }
        return checkIfReleased(checkThis);
    }
       
    /**
     * Public facing servlet to delete a given annotation. 
     */
    public void deleteObject() throws IOException, ServletException, Exception{
        if(null!=processRequestBody(request, true) && methodApproval(request, "delete")){ 
            BasicDBObject query = new BasicDBObject();
            BasicDBObject originalObject;
            //processRequestBody will always return a stringified JSON object here, even if the ID provided was a string in the body.
            JSONObject received = JSONObject.fromObject(content);
            JSONObject safe_received;
            JSONObject updatedWithFlag = new JSONObject();
            BasicDBObject updatedObjectWithDeletedFlag;
            System.out.println("Delete this recieved");
            System.out.println(received);
            if(received.containsKey("@id")){
                query.append("@id", received.getString("@id"));
                BasicDBObject mongo_obj = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query);
                safe_received = JSONObject.fromObject(mongo_obj); //We can trust this is the object as it exists in mongo
                System.out.println("Safe received obj is ");
                System.out.println(safe_received);
                boolean alreadyDeleted = checkIfDeleted(safe_received);
                boolean permission = false;
                boolean isReleased = false;
                boolean passedAllChecks = false;
                if(alreadyDeleted){
                    writeErrorResponse("Object for delete is already deleted.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                else{
                    isReleased = checkIfReleased(safe_received);
                    if(isReleased){
                        writeErrorResponse("This object is in a released state and cannot be deleted.", HttpServletResponse.SC_METHOD_NOT_ALLOWED);  
                    }
                    else{
                        permission = checkApplicationPermission(safe_received);
                        if(permission){
                           passedAllChecks = true;
                        }
                        else{
                           writeErrorResponse("Only the application that created this object can delete it.", HttpServletResponse.SC_UNAUTHORIZED);   
                        }
                    }
                }
                if(passedAllChecks){ //If all checks have passed.  If not, we want to make sure their writeErrorReponse() don't stack.  
                    originalObject = (BasicDBObject) JSON.parse(safe_received.toString()); //The original object out of mongo for persistance
                    //Found the @id in the object, but does it exist in RERUM?
                    if(null != originalObject){
                        String preserveID = safe_received.getString("@id");
                        JSONObject deletedFlag = new JSONObject(); //The __deleted flag is a JSONObject
                        deletedFlag.element("object", originalObject);
                        deletedFlag.element("deletor", "TODO"); //@cubap I assume this will be an API key?
                        deletedFlag.element("time", System.currentTimeMillis());
                        updatedWithFlag.element("@id", preserveID);
                        updatedWithFlag.element("__deleted", deletedFlag); //We want everything wrapped in deleted except the @id.
                        Object forMongo = JSON.parse(updatedWithFlag.toString()); //JSONObject cannot be converted to BasicDBObject
                        updatedObjectWithDeletedFlag = (BasicDBObject) forMongo;
                        boolean treeHealed = healHistoryTree(JSONObject.fromObject(originalObject));
                        if(treeHealed){
                            mongoDBService.update(Constant.COLLECTION_ANNOTATION, originalObject, updatedObjectWithDeletedFlag);
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }
                        else{
                            //@cubap @theHabes FIXME By default, objects that don't have the history property will fail to this line.
                            writeErrorResponse("We could not update the history tree correctly.  The delete failed.", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        }
                    }
                    else{
                        writeErrorResponse("The '@id' string provided for DELETE could not be found in RERUM: "+safe_received.getString("@id")+". \n DELETE failed.", HttpServletResponse.SC_NOT_FOUND);
                    }
                }
            }
            else{
                writeErrorResponse("Object for delete did not contain an '@id'.  Could not delete.", HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }
    
    /**
     * An internal method to handle when an object is deleted and the history tree around it will need amending.  
     * This function should only be handed a reliable object from mongo.
     * 
     * @param obj A JSONObject of the object being deleted.
     * @return A boolean representing whether or not this function succeeded. 
     */
     private boolean healHistoryTree(JSONObject obj){
         boolean success = true;
         String previous_id = "";
         String prime_id = "";
         JSONArray next_ids = new JSONArray();
         try{
             //Try to dig down the object and get these properties
            previous_id = obj.getJSONObject("__rerum").getJSONObject("history").getString("previous");
            prime_id = obj.getJSONObject("__rerum").getJSONObject("history").getString("prime");
            next_ids = obj.getJSONObject("__rerum").getJSONObject("history").getJSONArray("next");
         }
         catch(Exception e){
            //@cubap @theHabes #44.  What if obj does not have __rerum or __rerum.history            
            previous_id = ""; //This ensures detectedPrevious is false
            prime_id = ""; //This ensures isRoot is false
            next_ids = new JSONArray(); //This ensures the loop below does not run.
            success = false; //This will bubble out to deleteObj() and have the side effect that this object is not deleted.  @see treeHealed
         }
         boolean isRoot = prime_id.equals("root"); 
         boolean detectedPrevious = !previous_id.equals("");
         //Update the history.previous of all the next ids in the array of the deleted object
         for(int n=0; n<next_ids.size(); n++){
             BasicDBObject query = new BasicDBObject();
             BasicDBObject objToUpdate;
             BasicDBObject objWithUpdate;
             String nextID = next_ids.getString(n);
             query.append("@id", nextID);
             objToUpdate = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query); 
             if(null != objToUpdate){
                JSONObject fixHistory = JSONObject.fromObject(objToUpdate);
                if(isRoot){ //The object being deleted was root.  That means these next objects must become root.  Strictly, all history trees must have num(root) > 0.  
                    fixHistory.getJSONObject("__rerum").getJSONObject("history").element("prime", "root");
                    newTreePrime(fixHistory);
                }
                else if(detectedPrevious){ //The object being deleted had a previous.  That is now absorbed by this next object to mend the gap.  
                    fixHistory.getJSONObject("__rerum").getJSONObject("history").element("previous", previous_id);
                }
                else{
                    System.out.println("object did not have previous and was not root.  Weird...");
                    // @cubap @theHabes TODO Yikes this is some kind of error...it is either root or has a previous, this case means neither are true.
                    // cubap: Since this is a __rerum error and it means that the object is already not well-placed in a tree, maybe it shouldn't fail to delete?
                    // theHabes: Are their bad implications on the relevant nodes in the tree that reference this one if we allow it to delete?  Will their account of the history be correct?
                    //success = false;
                }
                Object forMongo = JSON.parse(fixHistory.toString()); //JSONObject cannot be converted to BasicDBObject
                objWithUpdate = (BasicDBObject)forMongo;
                mongoDBService.update(Constant.COLLECTION_ANNOTATION, objToUpdate, objWithUpdate);
             }
             else{
                 System.out.println("could not find an object assosiated with id found in history tree");
                 success = false;
                 //Yikes this is an error, could not find an object assosiated with id found in history tree.
             }
         }
         if(detectedPrevious){ 
             //The object being deleted had a previous.  That previous object next[] must be updated with the deleted object's next[].
             BasicDBObject query2 = new BasicDBObject();
             BasicDBObject objToUpdate2;
             BasicDBObject objWithUpdate2;
             query2.append("@id", previous_id);
             objToUpdate2 = (BasicDBObject) mongoDBService.findOneByExample(Constant.COLLECTION_ANNOTATION, query2); 
             if(null != objToUpdate2){
                JSONObject fixHistory2 = JSONObject.fromObject(objToUpdate2); 
                JSONArray origNextArray = fixHistory2.getJSONObject("__rerum").getJSONObject("history").getJSONArray("next");
                JSONArray newNextArray = new JSONArray();  
                //JSONArray does not have splice, but we can code our own.  This will splice out obj["@id"].
                for (int i=0; i<origNextArray.size(); i++){ 
                    if (!origNextArray.getString(i).equals(obj.getString("@id"))){
                        //So long as the value is not the deleted id, add it to the newNextArray (this is the splice).  
                        newNextArray.add(origNextArray.get(i));
                    }
                } 
                newNextArray.addAll(next_ids); //Adds next array of deleted object to the end of this array in order.
                fixHistory2.getJSONObject("__rerum").getJSONObject("history").element("next", newNextArray); //Rewrite the next[] array to fix the history
                Object forMongo2 = JSON.parse(fixHistory2.toString()); //JSONObject cannot be converted to BasicDBObject
                objWithUpdate2 = (BasicDBObject)forMongo2;
                mongoDBService.update(Constant.COLLECTION_ANNOTATION, objToUpdate2, objWithUpdate2);
             }
             else{
                 //Yikes this is an error.  We had a previous id in the object but could not find it in the store.
                 System.out.println("We had a previous id in the object but could not find it in the store");
                 success = false;
             }
         }
         return success;
     }
     
     /**
     * An internal method to make all descendants of this JSONObject take on a new history.prime = this object's @id
     * This should only be fed a reliable object from mongo
     * @param obj A new prime object whose descendants must take on its id
     */
     private boolean newTreePrime(JSONObject obj){
         boolean success = true;
         String primeID = obj.getString("@id");
         JSONArray descendants = new JSONArray();
         for(int n=0; n< descendants.size(); n++){
             JSONObject descendantForUpdate = descendants.getJSONObject(n);
             JSONObject originalDescendant = descendants.getJSONObject(n);
             BasicDBObject objToUpdate = (BasicDBObject)JSON.parse(originalDescendant.toString());;
             descendantForUpdate.getJSONObject("__rerum").getJSONObject("history").element("prime", primeID);
             BasicDBObject objWithUpdate = (BasicDBObject)JSON.parse(descendantForUpdate.toString());
             mongoDBService.update(Constant.COLLECTION_ANNOTATION, objToUpdate, objWithUpdate);
         }
         return success;
     }
     
     /**
     * Validate data is IIIF compliant against IIIF's validator.  This object is intended for creation and not yet saved into RERUM so it does not yet have an @id.
     * The only way to hit the IIIF Validation API is to use the object's @id. The idea would be to save the objects to get an id, hit the 
     * IIIF API and if the object was intended to be IIIF, delete it from the store and return an error to the user.
     * 
     * In the case the object was not intended to be IIIF, do not return an error. Since the methods calling this will handle what happens based of iiif_return.okay,
     * just set okay to 1 and the methods calling this will treat it as if it isn't a problem. 
     * 
     * @param objectToCheck A JSON object to parse through and validate.  This object has not yet been saved into mongo, so it does not have an @id yet
     * @param intendedIIIF A flag letting me know whether or not this object is intended to be IIIF.  If it isn't, don't treat validation failure as an error.
     * @return iiif_return The return JSONObject from hitting the IIIF Validation API.
     */
    public JSONObject checkIIIFCompliance(JSONObject objectToCheck, boolean intendedIIIF) throws MalformedURLException, IOException{
        JSONObject iiif_return = new JSONObject();
        DBObject dbo = (DBObject) JSON.parse(objectToCheck.toString());
        BasicDBObject dboWithObjectID = new BasicDBObject((BasicDBObject)dbo);
        String newObjectID = mongoDBService.save(Constant.COLLECTION_ANNOTATION, dbo);
        String uid = "http://devstore.rerum.io/rerumserver/id/"+newObjectID;
        dboWithObjectID.append("@id", uid);
        mongoDBService.update(Constant.COLLECTION_ANNOTATION, dbo, dboWithObjectID);
        iiif_return = checkIIIFCompliance(uid, "2.1"); //If it is an object we are creating, this line means @context must point to Presentation API 2 or 2.1
        if(iiif_return.getInt("okay") == 0){
            if(intendedIIIF){
                //If it was intended to be a IIIF object, then remove this object from the store because it was not valid and return an error to the user
                
            }
            else{
                //Otherwise say it is ok so the action looking do validate does not writeErrorResponse()
                iiif_return.element("okay", 1);
            }
        }
        iiif_return.remove("received");
        BasicDBObject query = new BasicDBObject();
        query.append("_id", newObjectID);
        mongoDBService.delete(Constant.COLLECTION_ANNOTATION, query);
        return iiif_return;
    }
    
     /**
     * A JSONObject that already has an @id can be validated against this IIIF validation URL.  It will return a JSONObject.
     * A save or update action could hit this to see if the resulting object is IIIF valid.  If not, a 'rollback' or 'delete' could be performed
     * and the warnings and errors sent back to the user.  If using this function, it is assumed objURL is intended to be a IIIF URL.
     * 
     * @param objURL The @id or id URL of a IIIF JSON object represented as a String.
     * @param version The Intended Presentation API version to validate against represented as a String.  (1, 2 or 2.1)
     * @return iiif_return The return JSONObject from hitting the IIIF Validation API.
     */
    public JSONObject checkIIIFCompliance(String objURL, String version) throws MalformedURLException, IOException{
        JSONObject iiif_return = new JSONObject();
        String iiif_validation_url = "http://iiif.io/api/presentation/validator/service/validate?format=json&version="+version+"&url="+objURL;
        URL validator = new URL(iiif_validation_url);
        BufferedReader reader = null;
        StringBuilder stringBuilder;
        HttpURLConnection connection = (HttpURLConnection) validator.openConnection();
        connection.setRequestMethod("GET"); //hmm... I think this is right
        connection.setReadTimeout(15*1000);
        connection.connect();
        reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        stringBuilder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null)
        {
          stringBuilder.append(line);
        }
        connection.disconnect();
        iiif_return = JSONObject.fromObject(stringBuilder.toString());
        iiif_return.remove("received");
        return iiif_return;
    }

    @Override
    public void setServletRequest(HttpServletRequest hsr) {
        this.request = hsr;
    }

    @Override
    public void setServletResponse(HttpServletResponse hsr) {
        this.response = hsr;
    }

    /**
     * @return the mongoDBService
     */
    public MongoDBService getMongoDBService() {
        return mongoDBService;
    }

    /**
     * @param mongoDBService the mongoDBService to set
     */
    public void setMongoDBService(MongoDBService mongoDBService) {
        this.mongoDBService = mongoDBService;
    }

    /**
     * @return the acceptedServer
     */
    public AcceptedServer getAcceptedServer() {
        return acceptedServer;
    }

    /**
     * @param acceptedServer the acceptedServer to set
     */
    public void setAcceptedServer(AcceptedServer acceptedServer) {
        this.acceptedServer = acceptedServer;
    }

    /**
     * @return the content
     */
    public String getContent() {
        return content;
    }

    /**
     * @param content the content to set
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * @return the oid
     */
    public String getOid() {
        return oid;
    }

    /**
     * @param oid the oid to set
     */
    public void setOid(String oid) {
        this.oid = oid;
    }

}

