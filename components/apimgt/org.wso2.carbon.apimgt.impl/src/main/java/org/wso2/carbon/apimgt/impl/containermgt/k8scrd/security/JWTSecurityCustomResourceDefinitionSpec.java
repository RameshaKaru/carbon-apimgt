/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.impl.containermgt.k8scrd.security;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;

@JsonDeserialize(
        using = JsonDeserializer.None.class
)

public class JWTSecurityCustomResourceDefinitionSpec implements KubernetesResource {

    private String type; //JWT_TYPE
    private String certificate; //SECURITY_CERTIFICATE
    private String issuer; //JWT_TOKEN_ISSUER
    private String audience; //JWT_AUDIENCE

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }

    /**
     * Creates following json object
     * type: ${type}
     * certificate: ${certificate}
     * issuer: ${issuer}
     * audience: ${audience}
     *
     * @return
     */
    @Override
    public String toString() {
        return "JWTSpec{" +
                "type='" + type + '\'' +
                ", certificate='" + certificate + '\'' +
                ", issuer='" + issuer + '\'' +
                ", audience='" + audience + '\'' +
                '}';
    }
}