/*
 *  Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.apimgt.impl.istiomgt;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.solr.common.StringUtils;
import org.bouncycastle.util.Strings;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.containermgt.K8sClient;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import org.wso2.carbon.apimgt.impl.istiomgt.istiocrd.*;

import java.io.IOException;
import java.util.*;

/**
 * Publishes istio related resources
 */

public class IstioDeployer {

    private String istioSystemNamespace;
    private String appNamespace;
    private static final String ISTIO_SYSTEM_NAMESPACE = "istio-system";
    private static final String APP_NAMESPACE = "default";
    private static final String K8S_SERVICE_DOMAIN = "svc.cluster.local";

    private CustomResourceDefinition ruleCRD;
    private CustomResourceDefinition httpAPISpecCRD;
    private CustomResourceDefinition httpAPISpecBindingCRD;

    private static final String TRY_KUBE_CONFIG = "kubernetes.auth.tryKubeConfig";
    private static final String TRY_SERVICE_ACCOUNT = "kubernetes.auth.tryServiceAccount";
    private KubernetesClient client;
    public static String CRD_GROUP = "config.istio.io";
    public static String HTTPAPISpecBinding_CRD_NAME = "httpapispecbindings." + CRD_GROUP;
    public static String HTTPAPISpec_CRD_NAME = "httpapispecs." + CRD_GROUP;
    public static String RULE_CRD_NAME = "rules." + CRD_GROUP;

    private static final Logger log = LoggerFactory.getLogger(IstioDeployer.class);


    /**
     * Publish Istio resources
     *
     */
    public boolean publishToIstio(K8sClient k8sClient, APIIdentifier apiIdentifier, API api) throws ParseException {

        //kubernetesServiceDomain = K8S_SERVICE_DOMAIN;
        appNamespace = APP_NAMESPACE;
        istioSystemNamespace = ISTIO_SYSTEM_NAMESPACE;



        try {

            String apiName = apiIdentifier.getApiName();

            String endPoint = getAPIEndpoint(api.getEndpointConfig());

            if (!endPoint.contains(K8S_SERVICE_DOMAIN)) {
                log.info("Istio resources are not getting deployed as the endpoint is not an Istio Service!");
                return true;
            }

            String apiVersion = apiIdentifier.getVersion();
            log.info("version :"+apiVersion);
            String apiContext = api.getContext();
            log.info("context :"+apiContext);
            String istioServiceName = getIstioServiceNameFromAPIEndPoint(endPoint);
            log.info("istioServiceName: "+istioServiceName);
            Set<URITemplate> uriTemplates = api.getUriTemplates();
            HashMap<String, String> resourceScopes = ApiMgtDAO.getInstance().getResourceToScopeMapping(api.getId());

            setupClient(k8sClient);
            setupCRDs();
            createHTTPAPISpec(apiName, apiContext, apiVersion, uriTemplates, resourceScopes);
            createHttpAPISpecBinding(apiName, istioServiceName);
            createRule(apiName, istioServiceName);

            return true;

        } catch (APIManagementException e) {
            log.error("Failed to publish service to API store while executing IstioDeployer. ", e);
        } catch (ParseException e) {
            log.error("Failed to parse API endpoint config while executing IstioDeployer. ", e);
        }

        return false;
    }

    /**
     * Extract Istio Service name from the endpoint
     *
     * @param endPoint Endpoint of the API
     * @return Returns Istio Service Name
     */
    private String getIstioServiceNameFromAPIEndPoint(String endPoint) throws APIManagementException {

        try {
            URL url = new URL(endPoint);
            String hostname = url.getHost();
            String[] hostnameParts = hostname.split("\\.");

            return hostnameParts[0];
        } catch (MalformedURLException e) {
            log.error("Malformed URL found for the endpoint.", e);
        }

        throw new APIManagementException("Istio Service Name could not found for the given API endpoint");
    }

    /**
     * Setup client for publishing
     */
    public void setupClient(K8sClient k8sClient) {

        if (this.client == null) {
            //client = new DefaultKubernetesClient(buildConfig());
            this.client = k8sClient.createClient(); //creating the client
        }
    }

    /**
     * Setting up custom resources
     */
    public void setupCRDs() {

        CustomResourceDefinitionList crds = client.customResourceDefinitions().list();
        List<CustomResourceDefinition> crdsItems = crds.getItems();

        for (CustomResourceDefinition crd : crdsItems) {
            ObjectMeta metadata = crd.getMetadata();
            if (metadata != null) {
                String name = metadata.getName();
                log.info("crd name: "+name);
                if (RULE_CRD_NAME.equals(name)) {
                    ruleCRD = crd;
                } else if (HTTPAPISpec_CRD_NAME.equals(name)) {
                    httpAPISpecCRD = crd;
                } else if (HTTPAPISpecBinding_CRD_NAME.equals(name)) {
                    httpAPISpecBindingCRD = crd;
                }
            }
        }

    }

    /**
     * Create HTTPAPISpecBinding for the API
     *
     * @param apiName  Name of the API
     * @param endPoint Endpoint of the API
     */
    public void createHttpAPISpecBinding(String apiName, String endPoint) {

        NonNamespaceOperation<HTTPAPISpecBinding, HTTPAPISpecBindingList, DoneableHTTPAPISpecBinding, io.fabric8.kubernetes.client.dsl.Resource<HTTPAPISpecBinding, DoneableHTTPAPISpecBinding>> bindingClient = client
                .customResource(httpAPISpecBindingCRD, HTTPAPISpecBinding.class, HTTPAPISpecBindingList.class,
                        DoneableHTTPAPISpecBinding.class).inNamespace(appNamespace);

        String bindingName = Strings.toLowerCase(apiName) + "-binding";
        String apiSpecName = Strings.toLowerCase(apiName) + "-apispec";

        HTTPAPISpecBinding httpapiSpecBinding = new HTTPAPISpecBinding();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(bindingName);
        httpapiSpecBinding.setMetadata(metadata);
        HTTPAPISpecBindingSpec httpapiSpecBindingSpec = new HTTPAPISpecBindingSpec();

        APISpec apiSpec = new APISpec();
        apiSpec.setName(apiSpecName);
        apiSpec.setNamespace(appNamespace);
        ArrayList<APISpec> apiSpecsList = new ArrayList<>();
        apiSpecsList.add(apiSpec);
        httpapiSpecBindingSpec.setApi_specs(apiSpecsList);

        Service service = new Service();
        service.setName(endPoint);
        service.setNamespace(appNamespace);
        ArrayList<Service> servicesList = new ArrayList<>();
        servicesList.add(service);
        httpapiSpecBindingSpec.setServices(servicesList);
        httpapiSpecBinding.setSpec(httpapiSpecBindingSpec);

        bindingClient.createOrReplace(httpapiSpecBinding);
        log.info("[HTTPAPISpecBinding] " + bindingName + " Created in the [Namespace] " + appNamespace + " for the"
                + " [API] " + apiName);
    }

    /**
     * Create HTTPAPISpec for the API
     *
     * @param apiName        Name of the API
     * @param apiContext     Context of the API
     * @param apiVersion     Version of the API
     * @param uriTemplates   URI templates of the API
     * @param resourceScopes Scopes of the resources of the API
     */
    public void createHTTPAPISpec(String apiName, String apiContext, String apiVersion, Set<URITemplate> uriTemplates,
            HashMap<String, String> resourceScopes) {

        NonNamespaceOperation<HTTPAPISpec, HTTPAPISpecList, DoneableHTTPAPISpec, io.fabric8.kubernetes.client.dsl.Resource<HTTPAPISpec, DoneableHTTPAPISpec>> apiSpecClient = client
                .customResource(httpAPISpecCRD, HTTPAPISpec.class, HTTPAPISpecList.class, DoneableHTTPAPISpec.class)
                .inNamespace(appNamespace);

        String apiSpecName = Strings.toLowerCase(apiName) + "-apispec";

        HTTPAPISpec httpapiSpec = new HTTPAPISpec();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(apiSpecName);
        httpapiSpec.setMetadata(metadata);

        HTTPAPISpecSpec httpapiSpecSpec = new HTTPAPISpecSpec();
        Map<String, Map<String, String>> attributeList = new HashMap<>();

        Map<String, String> apiService = new HashMap<>();
        apiService.put("stringValue", apiName);
        attributeList.put("api.service", apiService);

        Map<String, String> apiContextValue = new HashMap<>();
        apiContextValue.put("stringValue", apiContext);
        attributeList.put("api.context", apiContextValue);

        Map<String, String> apiVersionValue = new HashMap<>();
        apiVersionValue.put("stringValue", apiVersion);
        attributeList.put("api.version", apiVersionValue);

        ArrayList<Map<String, String>> apiKeys = new ArrayList<>();
        Map<String, String> apiKeysQuery = new HashMap<>();
        apiKeysQuery.put("query", "apikey");
        Map<String, String> apiKeysHeader = new HashMap<>();
        apiKeysHeader.put("header", "x-api-key");
        apiKeys.add(apiKeysQuery);
        apiKeys.add(apiKeysHeader);
        httpapiSpecSpec.setApiKeys(apiKeys);


        Attributes attributes = new Attributes();
        attributes.setAttributes(attributeList);
        httpapiSpecSpec.setAttributes(attributes);
        httpapiSpecSpec.setPatterns(getPatterns(apiContext, apiVersion, uriTemplates, resourceScopes));
        httpapiSpec.setSpec(httpapiSpecSpec);

        apiSpecClient.createOrReplace(httpapiSpec);
        log.info("[HTTPAPISpec] " + apiSpecName + " Created in the [Namespace] " + appNamespace + " for the" + " [API] "
                + apiName);
    }

    /**
     * Create Mixer rule for the API
     *
     * @param apiName     Name of the API
     * @param serviceName Istio service name
     */
    private void createRule(String apiName, String serviceName) {

        NonNamespaceOperation<Rule, RuleList, DoneableRule, io.fabric8.kubernetes.client.dsl.Resource<Rule, DoneableRule>> ruleCRDClient = client
                .customResource(ruleCRD, Rule.class, RuleList.class, DoneableRule.class)
                .inNamespace(istioSystemNamespace);
        String ruleName = Strings.toLowerCase(apiName) + "-rule";

        Rule rule = new Rule();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(ruleName);
        rule.setMetadata(metadata);

        Action action = new Action();
        String handlerName = "wso2-handler." + istioSystemNamespace;
        action.setHandler(handlerName);
        ArrayList<String> instances = new ArrayList<>();
        instances.add("wso2-authorization");
        instances.add("wso2-metrics");
        action.setInstances(instances);

        ArrayList<Action> actions = new ArrayList<>();
        actions.add(action);

        RuleSpec ruleSpec = new RuleSpec();
        String matchValue = "context.reporter.kind == \"inbound\" && destination.namespace == \"" + appNamespace
                + "\" && destination.service.name == \"" + serviceName + "\"";
        ruleSpec.setMatch(matchValue);
        ruleSpec.setActions(actions);
        rule.setSpec(ruleSpec);

        ruleCRDClient.createOrReplace(rule);
        log.info("[Rule] " + ruleName + " Created in the [Namespace] " + istioSystemNamespace + " for the [API] "
                + apiName);

    }

    /**
     * Get API endpoint from the API endpoint config
     *
     * @param endPointConfig Endpoint config of the API
     * @return Returns API endpoint url
     */
    private String getAPIEndpoint(String endPointConfig) throws ParseException {

        if (endPointConfig != null) {
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = null;
            endpointConfigJson = (JSONObject) parser.parse(endPointConfig);

            if (endpointConfigJson.containsKey("production_endpoints")) {
                return getEPUrl(endpointConfigJson.get("production_endpoints"));
            }
        }

        return "";
    }

    /**
     * Verify whether the endpoint url is non empty
     *
     * @param endpoints Endpoint objects
     * @return Returns if endpoint is not empty
     */
    private static boolean isEndpointURLNonEmpty(Object endpoints) {
        if (endpoints instanceof JSONObject) {
            JSONObject endpointJson = (JSONObject) endpoints;
            if (endpointJson.containsKey("url") && endpointJson.get("url") != null) {
                String url = endpointJson.get("url").toString();
                //if (org.apache.commons.lang.StringUtils.isNotBlank(url)) {
                log.info("url :"+url);
                    return true;
                //}
            }
        } else if (endpoints instanceof org.json.simple.JSONArray) {
            org.json.simple.JSONArray endpointsJson = (JSONArray) endpoints;

            for (int i = 0; i < endpointsJson.size(); ++i) {
                if (isEndpointURLNonEmpty(endpointsJson.get(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Extract Endpoint url from the endpoints object
     *
     * @param endpoints Endpoints object
     * @return Returns endpoint url
     */
    private String getEPUrl(Object endpoints) {

        if (endpoints instanceof JSONObject) {
            JSONObject endpointJson = (JSONObject) endpoints;
            if (endpointJson.containsKey("url") && endpointJson.get("url") != null) {
                String url = endpointJson.get("url").toString();
                //if (org.apache.commons.lang.StringUtils.isNotBlank(url)) {
                log.info("url2 :"+url);
                    return url;
                //}
            }
        } else if (endpoints instanceof org.json.simple.JSONArray) {
            org.json.simple.JSONArray endpointsJson = (JSONArray) endpoints;

            for (int i = 0; i < endpointsJson.size(); ++i) {
                if (isEndpointURLNonEmpty(endpointsJson.get(i))) {
                    return endpointsJson.get(i).toString();
                }
            }
        }

        return "";
    }

    /**
     * Get patterns for the given api
     *
     * @param apiContext     Context of the API
     * @param apiVersion     Version of the API
     * @param uriTemplates   URI templates of the API
     * @param resourceScopes Scopes of the resources of the API
     * @return Returns list of patterns
     */
    private ArrayList<Pattern> getPatterns(String apiContext, String apiVersion, Set<URITemplate> uriTemplates,
            HashMap<String, String> resourceScopes) {

        ArrayList<Pattern> patterns = new ArrayList<>();

        for (URITemplate uriTemplate : uriTemplates) {

            String resourceUri = uriTemplate.getUriTemplate();
            String httpMethod = uriTemplate.getHTTPVerb();
            Pattern pattern = new Pattern();
            pattern.setHttpMethod(httpMethod);
            pattern.setUriTemplate(resourceUri);

            Map<String, Map<String, String>> attributeList = new HashMap<>();

            Map<String, String> apiOperation = new HashMap<>();
            String apiOperationValue = "\"" + resourceUri + "\"";
            apiOperation.put("stringValue", apiOperationValue);
            attributeList.put("api.operation", apiOperation);

            String scopeKey = APIUtil.getResourceKey(apiContext, apiVersion, resourceUri, httpMethod);

            if (resourceScopes != null) {
                String scope = resourceScopes.get(scopeKey);

                if (scope != null) {
                    Map<String, String> resourceScope = new HashMap<>();
                    String resourceScopeValue = scope;
                    resourceScope.put("stringValue", resourceScopeValue);
                    attributeList.put("resource.scope", resourceScope);
                }
            }

            Attributes attributes = new Attributes();
            attributes.setAttributes(attributeList);
            pattern.setAttributes(attributes);

            patterns.add(pattern);
        }

        return patterns;
    }
}

