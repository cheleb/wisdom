/*
 * #%L
 * Wisdom-Framework
 * %%
 * Copyright (C) 2015 Wisdom Framework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.wisdom.raml.visitor;

import org.raml.model.*;
import org.raml.model.parameter.AbstractParam;
import org.raml.model.parameter.FormParameter;
import org.raml.model.parameter.QueryParameter;
import org.raml.model.parameter.UriParameter;
import org.wisdom.source.ast.model.ControllerModel;
import org.wisdom.source.ast.model.ControllerRouteModel;
import org.wisdom.source.ast.model.RouteParamModel;
import org.wisdom.source.ast.visitor.Visitor;

import java.util.*;

import static java.util.Collections.singletonList;

/**
 * <p>
 * {@link ControllerModel} visitor implementation. It populates a given {@link Raml} model thanks to the
 * wisdom {@link ControllerModel}.
 * </p>
 *
 * @author barjo
 */
public class RamlControllerVisitor implements Visitor<ControllerModel<Raml>,Raml> {

    /**
     * Visit the Wisdom Controller source model in order to populate the raml model.
     *
     * @param element The wisdom controller model (we visit it).
     * @param raml The raml model (we construct it).
     */
    @Override
    public void visit(ControllerModel element,Raml raml) {
        raml.setTitle(element.getName());

        if(element.getDescription() != null && !element.getDescription().isEmpty()){
            DocumentationItem doc = new DocumentationItem();
            doc.setContent(element.getDescription());
            doc.setTitle("Description");
            raml.setDocumentation(singletonList(doc));
        }

        if(element.getVersion()!=null && !element.getVersion().isEmpty()){
            raml.setVersion(element.getVersion());
        }

        //noinspection unchecked
        navigateTheRoutes(element.getRoutes(), null,raml);
    }

    /**
     * Navigate through the Controller routes, and create {@link org.raml.model.Resource} from them.
     * If the <code>parent</code> is not null, then the created route will be added has children of the parent, otherwise
     * a new Resource is created and will be added directly to the <code>raml</code> model.
     *
     * @param routes The @{link ControllerRoute}
     * @param parent The parent {@link Resource}
     * @param raml The {@link Raml} model
     */
    private void navigateTheRoutes(NavigableMap<String, Collection<ControllerRouteModel<Raml>>> routes, Resource parent,Raml raml){
        //nothing to see here
        if (routes == null || routes.isEmpty()){
            return; //
        }

        String headUri = routes.firstKey();

        Collection<ControllerRouteModel<Raml>> brotherElems = routes.get(headUri);

        Resource res = new Resource();

        if(parent!=null) {
            res.setParentResource(parent);
            res.setParentUri(parent.getUri());
            //Get the relative part of the url
            res.setRelativeUri(extractRelativeUrl(headUri, parent.getUri()));
            parent.getResources().put(res.getRelativeUri(), res);
        }else {
            res.setParentUri("");
            res.setRelativeUri(extractRelativeUrl(headUri, null));
            //update raml
            raml.getResources().put(res.getRelativeUri(),res);

        }

        //Add the action from the brother routes
        for(ControllerRouteModel<Raml> bro : brotherElems){
            addActionFromRouteElem(bro, res);
        }

        //visit the children route
        NavigableMap<String,Collection<ControllerRouteModel<Raml>>> child = routes.tailMap(headUri, false);

        //no more route element
        if(child.isEmpty()){
            return;
        }

        if(child.firstKey().startsWith(headUri)){
            navigateTheRoutes(child, res, raml); //current resource is the parent
        }else{
            navigateTheRoutes(child, parent, raml); //same parent as this resource
        }
    }

    /**
     * Get the relative route uri from its headUri/fullUri and parentUri.
     * @param headUri the route full uri.
     * @param parentUri the route parent uri.
     * @return The route relative uri.
     */
    private static String extractRelativeUrl(String headUri, String parentUri){
        String url;

        //Get the relative part of the url, and remove last character `/` (added for ordering)
        if(parentUri == null || parentUri.isEmpty()){
            url = headUri.substring(0,headUri.length()-1);
        }else {
            url = headUri.substring(parentUri.length(),headUri.length()-1);
        }

        if(url.isEmpty()){
            return "/";
        }

        return url;
    }

    /**
     * Add the body specification to the given action.
     *
     * <p>
     * Body can contain one example for each content-type supported. The example must be define in the same order as
     * the content-type.
     *
     * </p>
     * @param elem The ControllerRouteModel that contains the body specification.
     * @param action The Action on which to add the body specification.
     */
    private void addBodyToAction(ControllerRouteModel<Raml> elem, Action action){
        action.setBody(new LinkedHashMap<String, MimeType>(elem.getBodyMimes().size()));

        //the samples must be define in the same order as the accept!
        Iterator<String> bodySamples = elem.getBodySamples().iterator();

        for(String mime: elem.getBodyMimes()){
            MimeType mimeType = new MimeType(mime);
            if(bodySamples.hasNext()) {
                mimeType.setExample(bodySamples.next());
            }
            action.getBody().put(mime,mimeType);
        }
    }

    /**
     * Add the response specification to the given action.
     *
     * @param elem The ControllerRouteModel that contains the response specification.
     * @param action The Action on which to add the body specification.
     */
    private void addResponsesToAction(ControllerRouteModel<Raml> elem, Action action){
        for(String mime: elem.getResponseMimes()){
            Response resp = new Response();
            resp.setBody(Collections.singletonMap(mime, new MimeType(mime)));
            action.getResponses().put("200",resp); //TODO enhance with sample
        }
    }

    /**
     * Set the resource action from the wisdom route element.
     *
     * @param elem The wisdom route element that we are visiting
     * @param resource The raml resource corresponding to the route element
     */
    private void addActionFromRouteElem(ControllerRouteModel<Raml> elem, Resource resource){
        Action action = new Action();
        action.setType(ActionType.valueOf(elem.getHttpMethod().name()));

        action.setDescription(elem.getDescription());

        //handle body
        addBodyToAction(elem, action);

        //Handle responses
        addResponsesToAction(elem,action);

        //handle all route params
        for(RouteParamModel<Raml> param: elem.getParams()){

            AbstractParam ap = null; //the param to add

            //Fill the param info depending on its type
            switch (param.getParamType()){
                case FORM:
                    MimeType formMime = action.getBody().get("application/x-www-form-urlencoded");

                    if(formMime == null){
                        //create default form mimeType
                        formMime = new MimeType("application/x-www-form-urlencoded");
                    }

                    if(formMime.getFormParameters() == null){ //why raml, why you ain't init that
                        formMime.setFormParameters(new LinkedHashMap<String, List<FormParameter>>(2));
                    }

                    ap = new FormParameter();
                    formMime.getFormParameters().put(param.getName(), singletonList((FormParameter) ap));
                    break;
                case PARAM:
                case PATH_PARAM:
                    if (!ancestorOrIHasParam(resource, param.getName())) {
                        ap = new UriParameter();
                        resource.getUriParameters().put(param.getName(), (UriParameter) ap);
                    }
                    //we do nothing if the param has already been define in the resouce or its ancestor.
                    break;
                case QUERY:
                    ap = new QueryParameter();
                    action.getQueryParameters().put(param.getName(),(QueryParameter) ap);
                    break;
                case BODY:
                default:
                    break; //body is handled at the method level.
            }

            if(ap == null){
                //no param has been created, we skip.
                continue;
            }

            //Set param type
            ParamType type = typeConverter(param.getValueType());

            if(type != null) {
                ap.setType(type);
            }

            //set default value
            ap.setRequired(true); //required by default ? TODO use the optional annotations.

            if(param.getDefaultValue()!=null){
                ap.setRequired(false);
                ap.setDefaultValue(param.getDefaultValue());
            }

            if(param.isOptional()){
                ap.setRequired(false);
            }
        }

        resource.getActions().put(action.getType(),action);
    }

    /**
     * Check if the given resource or its ancestor have the uri param of given name.
     *
     * @param resource The resource on which to check.
     * @param uriParamName Name of the uri Param we are looking for.
     * @return <code>true</code> if this or its ancestor resource have the param of given name already define.
     */
    private static Boolean ancestorOrIHasParam(final Resource resource, String uriParamName){
        Resource ancestor = resource;

        while(ancestor!=null){
            if(ancestor.getUriParameters().containsKey(uriParamName)){
                return true;
            }
            ancestor = ancestor.getParentResource();
        }

        return false;
    }

    /**
     * Convert a string version of the type name into a ParamType enum or null if nothing correspond.
     *
     * @param typeName The type name.
     * @return the {@link ParamType} corresponding to the  given type name.
     */
    private static ParamType typeConverter(String typeName){
        if(typeName == null || typeName.isEmpty()){
            return null;
        }

        if("Number" .equals(typeName) || "Long".equalsIgnoreCase(typeName)
                || "Integer".equals(typeName) || "int".equals(typeName)){
            return ParamType.NUMBER;
        }

        if("Boolean".equalsIgnoreCase(typeName)){
            return ParamType.BOOLEAN;
        }

        if("String".equals(typeName)){
            return ParamType.STRING;
        }

        if("Date".equals(typeName)){
            return ParamType.DATE;
        }

        if("File".equals(typeName)){
            return ParamType.FILE;
        }

        return null;
    }
}
