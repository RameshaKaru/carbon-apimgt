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

package org.wso2.carbon.apimgt.impl.istiomgt.istiocrd;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;

import java.util.Map;

@JsonDeserialize(
        using = JsonDeserializer.None.class
)
public class Attributes implements KubernetesResource {

    private Map<String, Map<String, String>> attributes;

    public void setAttributes(Map<String, Map<String, String>> attributes) {
        this.attributes = attributes;
    }

    public Map<String, Map<String, String>> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "attributes{" + attributes + '}';
    }


}


