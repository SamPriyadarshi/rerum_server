# API (0.9)
<!-- TOC -->

- [API (0.9)](#api-09)
  - [GET](#get)
    - [Single object by id](#single-object-by-id)
    - [History tree before this version](#history-tree-before-this-version)
    - [History tree since this version](#history-tree-since-this-version)
  - [POST](#post)
    - [Create](#create)
    - [Batch Create (beta)](#batch-create-proposed)
    - [Custom Query (beta)](#custom-query-beta)
    - [Access Token Proxy](#access-token-proxy)
    - [Refresh Token Proxy](#refresh-token-proxy)
    - [HTTP POST Method Override](#http-post-method-override)
  - [PUT](#put)
    - [Update](#update)
    - [Batch Update (beta)](#batch-update-proposed)
  - [PATCH](#patch)
    - [Patch Update](#patch-update)
    - [Add Properties](#add-properties)
    - [Remove Properties](#remove-properties)
    - [RERUM released](#rerum-released)
  - [DELETE](#delete)
  - [Smart objects](#smart-objects)
  - [__rerum](#rerum)
    - [History](#history)
    - [`generatedBy` Attribution](#generatedby-attribution)
  - [Authentication](#authentication)
  - [@context](#context)
  - [IIIF](#iiif)
  - [Web Annotation](#web-annotation)
  - [RERUM Responses](#rerum-responses)

<!-- /TOC -->
All the following interactions will take place between
the server running RERUM and the application server.  A call
to one of these APIs from a front end will fail.  Please note that all
examples are pointing at the development version of the RERUM API, not the
production version.  Only point to the production version once you have
tested with the development version.

If you would like to see an example application front end that uses
its back end as a proxy to the RERUM API, visit http://tinydev.rerum.io.

If you want to see what an application API proxy looks like
that uses the RERUM API, visit our TinyThings GitHub example at
https://github.com/CenterForDigitalHumanities/TinyThings/tree/master/Source%20Packages/io/rerum/crud

To have simple CRUD ability from a front end without using a back end proxy, you can
use our public test endpoints.  Note: Your data could be removed at any time, this is for testing only.
- http://tinydev.rerum.io/app/create   Uses the rules established by RERUM [create](#create)
- http://tinydev.rerum.io/app/update   Uses the rules established by RERUM PUT [update](#update)
- http://tinydev.rerum.io/app/delete   Uses the rules established by RERUM [delete](#delete)
- http://tinydev.rerum.io/app/query    Uses the rules established by RERUM [Custom Query](#custom-query-beta)


## GET

### Single object by id

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/id/_id` | `empty` | 200: `{JSON}`

- **`_id`**—the id of the object in RERUM.
- **Response: `{JSON}`**—The object at `_id`

Example: http://devstore.rerum.io/v1/id/11111

### History tree before this version

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/history/_id` | `empty` | 200: `[{JSON}]`

- **`_id`**—the id of the object in RERUM.
- **Response: `[{JSON}]`**—an array of the resolved objects of all parent history objects

As objects in RERUM are altered, the previous state is retained in
a history tree. Requests return ancestors of this object on it's
branch.  The objects in the array are listed in inorder traversal but 
ignoring other branches.

Example: http://devstore.rerum.io/v1/history/11111

### History tree since this version

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/since/_id` | `empty` | 200: `[{JSON}]`

- **`_id`**—the id of the object in RERUM.
- **Response: `[{JSON}]`**—an array of the resolved objects of all child history objects

As objects in RERUM are altered, the next state is retained in
a history tree.  Requests return all descendants of this object from all branches.  
The objects in the array are listed in preorder traversal.

Example:  http://devstore.rerum.io/v1/since/11111

## POST

### Create

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/create.action` | `{JSON}` | 201: `header.Location` "Created @ `[@id]`", `{JSON}`

- **`{JSON}`**—The object to create
- **Response: `{JSON}`**—Containing various bits of information about the create.

Add a completely new object to RERUM and receive the location
in response.  Accepts only single JSON objects for RERUM storage.
Mints a new URI and returns the object's `Location` as a header.
If the object already contains an `@id` that matches an object in RERUM,
the API will direct the user to use [update](#update) instead.

Example Response:

- **Header:** `Location: Created @ http://devstore.rerum.io/v1/id/11111`
- **Body:**

~~~ (json)
{
  "code" : 201,
  "@id" : "http://devstore.rerum.io/v1/since/11111",
  "new_obj_state" : {
    "@id": "http://devstore.rerum.io/v1/id/11111",
    ...
  },
  "iiif_validation" : {
    "warnings" : ["Array of warnings from IIIF validator"],
    "error" : "Error for why this object failed validation",
    "okay" : 1 // 0 or 1 as to whether or not it passed IIIF validation
  }
}
~~~

### Access Token Proxy

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/accessToken.action` | `{JSON}` | 200: `{JSON}`

- **`{JSON}`**— Auth0 requirements [here](https://auth0.com/docs/tokens/refresh-token/current#use-a-refresh-token)
- **Response: `{JSON}`**— Containing the Auth0 /oauth/token `JSON` response

RERUM works as a proxy with Auth0 to help manage tokens from registered applications.
This will form the request necessary to get a response from Auth0 which contains
a new access token.

Example Response:

- **Header:** `HTTP/1.1 200 OK`
- **Body:**

~~~ (json)
{
  "access_token":"eyJz93a...k4laUWw",
  "token_type":"Bearer",
  "expires_in":86400
}
~~~

### Refresh Token Proxy

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/refreshToken.action` | `{JSON}` | 200: `{JSON}`

- **`{JSON}`**— Auth0 requirements [here](https://auth0.com/docs/tokens/refresh-token/current#get-a-refresh-token)
- **Response: `{JSON}`**— Containing the Auth0 /oauth/token `JSON` response

RERUM works as a proxy with Auth0 to help manage tokens from registered applications.
This will form the request necessary to get a response from Auth0 which contains
a new refresh token.

Example Response:

- **Header:** `HTTP/1.1 200 OK`
- **Body:**

~~~ (json)
{
  "access_token":"eyJz93a...k4laUWw",
  "refresh_token":"GEbRxBN...edjnXbL",
  "id_token":"eyJ0XAi...4faeEoQ",
  "token_type":"Bearer",
  "expires_in":86400
}
~~~

### Batch Create (proposed)

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/batchCreate.action` | `[{JSON}]` | 200: `[{JSON}]`

- **`[{JSON}]`**—an array of objects to create in RERUM
- **Response: `[{JSON}]`**—an array of the resolved objects from the creation process

The array of JSON objects passed in will be created in the
order submitted and the response will have the URI of the new
resource or an error message in the body as an array in the
same order.  When errors are encountered, the batch process
will attempt to continue for all submitted items.

### Custom Query (beta)

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/getByProperties.action` | `{JSON}` | 200: `[{JSON}]`

- **`{JSON}`**—the properties in JSON format for the query
- **Response: `[{JSON}]`**—an array of the resolved objects of all objects that match the query

This simple format will be made more complex
in the future, but should serve the basic needs as it is.
All responses are in a JSON Array, even if zero records or a single
record is returned.  RERUM will test for property matches, 
so `{ "@type" : "sc:Canvas", "label" : "page 46" }` will match

~~~ (json)
[{
  "@id": "https://devstore.rerum.io/v1/id/11111",
  "otherContent": [],
  "label": "page 46",
  "width": 730,
  "images": [],
  "height": 1000,
  "@type": "sc:Canvas"
}]
~~~

### HTTP POST Method Override

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/patch.action` | `{JSON}` | 201: `header.Location` "Created @ `[@id]`", `{JSON}`

- **`{JSON}`**—The object to patch update. 
- **Response: `{JSON}`**—Containing various bits of information about the patch.

Some programming languages and some servers do not consider `PATCH` to be a standard method.
As a result, some software is unable to make a `PATCH` update request.
RERUM still wants these applications to fit within these standards.  We support
the `X-HTTP-Method-Override` header on `POST` requests to make them act like `PATCH` requests
in this API.  

Example Method Override Request:

- **Header:** 
          `Method: POST`,
          `X-HTTP-Method-Override: PATCH`
- **Body:**
          **`{JSON}`**—An object containing the @id for upate and the fields in that object to patch. 
          
This grants software that is otherwise unable to make these requests the ability
to so.

Example Response:

- **Header:** `Location: Updated @ http://devstore.rerum.io/v1/id/22222`
- **Body:**

~~~ (json)
{
  "code" : 200,
  "original_object_id" : "http://devstore.rerum.io/v1/id/11111",
  "new_obj_state" : {
    "@id": "http://devstore.rerum.io/v1/id/22222"
    ...
  },
  "iiif_validation" : {
    "warnings" : ["Array of warnings from IIIF validator"],
    "error" : "Error for why this object failed validation",
    "okay" : 1 // 0 or 1 as to whether or not it passed IIIF validation
  }
}
~~~

## PUT

### Update

Replace an existing record through reference to its internal
RERUM id.  This will have the effects of update, set, and unset actions.
New keys will be created and keys not present in the request will be dropped.
When an object is updated, the `@id` will change, as the previous
version will maintain its place in the history of that object.

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/update.action` | `{JSON}` | 200: `header.Location` New state `{JSON}`

- **`{JSON}`**—The requested new state for the object.
- **Response Body: `{JSON}`**—Containing various bits of information about the PUT update.

Example Response:

- **Header:** `Location: Updated @ http://devstore.rerum.io/v1/id/22222`
- **Body:**

~~~ (json)
{
  "code" : 200,
  "original_object_id" : "http://devstore.rerum.io/v1/id/11111",
  "new_obj_state" : {
    "@id": "http://devstore.rerum.io/v1/id/22222",
    ...
  },
  "iiif_validation" : {
    "warnings" : ["Array of warnings from IIIF validator"],
    "error" : "Error for why this object failed validation",
    "okay" : 1 // 0 or 1 as to whether or not it passed IIIF validation
  }
}
~~~

### Batch Update (proposed)

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/batch_update.action` | `[{JSON}]` | 200: "[header.Location]" New state `[{JSON}]`

- **`[{JSON}]`**—an array of objects to update in RERUM.  Each object MUST contain an `@id`.
- **Response: `[{JSON}]`**—an array of the resolved objects in their new state from the update process

The array of JSON objects passed in will be updated in the
order submitted and the response will have the URI of the
resource or an error message in the body as an array in the
same order.

## PATCH

 >**NB:** `__rerum`, `@id` and `_id` updates are ignored.
 >
 > Updates to released or deleted objects fail with an error.

### Patch Update

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/patch.action` | `{JSON}` | 200: `header.Location` New state `{JSON}`

- **`{JSON}`**—The requested new state for the object.  MUST contain an `@id`
- **Response Body: `{JSON}`**—Containing various bits of information about the PATCH update.

A single object is updated by altering the set or subset of properties in the JSON
payload. This method only updates existing keys. If a property submitted in the payload which does not exist, an error will be returned to the user. If
`{key:null}` is submitted, the value will be set to `null`.
Properties not mentioned in the payload object remain unaltered.

Example Response:

- **Header:** `Location: Updated @ http://devstore.rerum.io/v1/id/22222`
- **Body:**

~~~ (json)
{
  "code" : 200,
  "original_object_id" : "http://devstore.rerum.io/v1/id/11111",
  "new_obj_state" : {
    "@id": "http://devstore.rerum.io/v1/id/22222",
    ...
  },
  "iiif_validation" : {
    "warnings" : ["Array of warnings from IIIF validator"],
    "error" : "Error for why this object failed validation",
    "okay" : 1 // 0 or 1 as to whether or not it passed IIIF validation
  }
}
~~~

### Add Properties

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/set.action` | `{JSON}` | 200: `header.Location` New state `{JSON}`

- **`{JSON}`**—The requested new state for the object MUST contain an `@id`
- **Response: `{JSON}`**—Containing various bits of information about the PATCH update. (see PUT Update for example)

A single object is updated by adding all properties in the JSON
payload. If a property already exists, a warning is returned to the user. 

### Remove Properties

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/unset.action` | `{JSON}` | 202: `header.Location` New state `{JSON}`

- **`{JSON}`**—The requested new state for the object.  Must contain an `@id`.
- **`{JSON}`**—Containing various bits of information about the PATCH update. (see PUT Update for example)

A single object is updated by dropping all properties
in the JSON payload list like `{key:null}`. Keys must match
to be dropped otherwise a warning is returned to the user.

### RERUM released

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/release.action` | `String @id` or `{JSON}` | 200: `header.Location` New state `{JSON}`

- **`String @id`**—The `@id` of the version to be released.
- **`{JSON}`**—The object.  Must contain `@id`.

RERUM allows for the `generatedBy` of a version of an object to assign a `released` state. Objects in released states are locked such that further changes are refused. Calling any update or delete action on a released object will result in an error response. The release action will perform an update to the `__rerum.isReleased` of the version identified and update `__rerum.releases` properties throughout the object's history. Any version of an object with an `oa:Motivation` containing `rr:releasing` will be released as soon as it is saved.

Example Response:
%)($NF)D*NS_$

## DELETE

RERUM allows the `generatedBy` of an object to delete that object.  
Requests can be made by the string `@id` or a JSON object containing the `@id`.
RERUM DELETE does not remove anything from the server. Deleted objects are only marked as deleted.
Objects marked as deleted do not return in query results and may only be directly retreived by `@id`.

| Patterns | Payloads | Responses
| ---     | ---     | ---
| `/delete.action` | `String @id` or `{JSON}` | 204

- **`String @id`**—The @id of the object.
- **`{JSON}`**—The object to delete.  Must contain `@id`.

There is no batch `DELETE` planned.

A deleted object is easily recognized:

~~~ (json)
{
  "@id" : "http://devstore.rerum.io/v1/id/11111",
  "__deleted" : {
    "object" : {
      "@id" : "http://devstore.rerum.io/v1/id/11111",
      "@type": "sc:Canvas"
      "label": "page 46",
      "width": 730,
      "images": [],
      "height": 1000,
      "__rerum": {...}
    },
    "deletor" : <generatedBy>,
    "time" : 1516213216852
  }
}
~~~

> **Note:** The `__deleted.object` contains a snapshot of this version of the object when it was deleted, including its place in the history.

___

## Smart objects

***Proposed development**

## __rerum

Each object carries a protected property called `__rerum` containing a metadata
object about the version retrieved.

| Property         | Type      | Description
| ---              | ---       | ---
| @context         | String    | The RERUM context file http://store.rerum.io/v1/context.json
| history.prime    | String    | The URI of the object initializing this history.
| history.next     | [String]  | An array of URIs for the immediate derivatives of this version. A length > 1 indicates a branch.
| history.previous | String    | The URI of the immediately previous version.
| generatedBy      | String    | Reference to the authenticated application which committed this version.
| createdAt        | timestamp | Though the object may also assert this about itself, RERUM controls this value.
| isOverwritten    | timestamp | Written when the overwrite endpoint is used. Exposes the date and time of the change.
| isReleased       | timestamp | Written when the release endpoint is used.  Exposes the date and time this node was released.
| releases.previous| String    | URI of the most recent released version from which this version is derived.
| releases.next    | [String]  | Array of URIs for the first `released` decendant in the downstream branches
| releases.replaces| String    | URI of the previous release this node is motivated to replace. This is only present on released versions and will always match the value of `releases.previous`.

>In the future, this may be encoded as an annotation on the object, using
existing vocabularies, but for now the applications accessing RERUM will
need to interpret this data if it is relevant.

### History

History is stored through pointers that create a B-Tree.  All nodes in the B-tree know the root node, the previous node, and the next node(s).

You can ask for all descendants or all ancestors from any given node so long as you know the node's `@id` and the node has not been deleted.  See [history parents](#history-tree-before-id) and [history children](#history-tree-since-id) for more details about this process.

Deleted objects are not present in any B-Tree, but do exist as separate nodes that can be requested by the URI directly.  A snapshot their position at the time of deletion persists in these deleted nodes.

### `generatedBy` Attribution

RERUM associates a `foaf:Agent` with each action performed on an item in `__rerum.generatedBy`. An API key authenticated application requesting an overwrite, release, or delete action can only do so if they are the `generatedBy` of the object the action is performed on. If an unauthorized application attempts one of these actions a `401 Unauthorized` response is returned with an explanation on how to branch versions instead.

Applications are _strongly_ encouraged to record their own assertions within the objects, as consuming applications may reliably use a combination of the authoritative `generatedBy` property and an intrinsic `creator` to establish a reliable attribution.

## Authentication

RERUM creates an `agent` for each successful registration. This `agent` is in JSON-LD format and stored publicly. Authentication is managed by [Auth0](https://auth0.com/). 
When RERUM creates an `agent`, Auth0 generates a refresh token and an access token.  Applications are responsible for providing their access tokens via a `Authentication` Header in their CRUD requests.  Get requests do not require this header.  As access tokens expire every hour, the applications are responsible for requesting and keeping track of valid access tokens.  For an example on how to do this, see this example from [TinyThings](https://github.com/CenterForDigitalHumanities/TinyThings/blob/master/Source%20Packages/io/rerum/tokens/TinyTokenManager.java).
The API key at Auth0 persists for each application, which may manage its own sessions. Expired (unauthorized) sessions receive a `401 Unauthorized` response with instructions to refresh the session or to register the application.

## @context

Objects in RERUM should be JSON-LD, which means they should have an `@context` provided when they are created.  However, ordinary JSON documents are allowed in the store. These JSON documents can be interpreted as JSON-LD by referencing a JSON-LD context document in an [HTTP Link Header](https://www.w3.org/TR/json-ld/#h3_interpreting-json-as-json-ld). RERUM provides this `@context` in the `Link` header and also provides an `@context` for the `__rerum` terms mentioned above.

http://store.rerum.io/v1/context.json

## IIIF

RERUM fully supports the IIIF standard and makes third party calls to the [IIIF validation API](http://iiif.io/api/presentation/validator/service/). A piece of the RERUM response is the validation response of this API so the user knows whether or not their data conforms to this standard. Objects that fail IIIF validation are still saved.

## Web Annotation

RERUM follows the W3C Annotation protocol.  To learn more about Web Annotation see https://www.w3.org/TR/annotation-protocol/

## RERUM Responses

The intention of the API is to follow RESTful practices.  These practices drive what requests we accept and what responses we have to the various scenarios around those requests. What it means to be RESTful varies wildly, but our efforts follow the guidelines at http://www.restapitutorial.com/resources.html

RERUM follows REST, IIIF and Web Annotation standards to form its responses to users.  For more information about why RERUM chose a certain HTTP status code see the graph below.

![alt text][chart]

[chart]: https://user-images.githubusercontent.com/3287006/32914301-b2fbf798-cada-11e7-9541-a2bee8454c2c.png

If you are confused as to what type of requests give what reponse, review the [Web Annotation](#web-annotation) and [RESTful](#rest) standards.

***Listing of responses is undergoing development at this time.**
