/*
 *  + *******************************************************************************
 *  + * Licensed to the Apache Software Foundation (ASF) under one
 *  + * or more contributor license agreements.  See the NOTICE file
 *  + * distributed with this work for additional information
 *  + * regarding copyright ownership.  The ASF licenses this file
 *  + * to you under the Apache License, Version 2.0 (the
 *  + * "License"); you may not use this file except in compliance
 *  + * with the License.  You may obtain a copy of the License at
 *  + *
 *  + *   http://www.apache.org/licenses/LICENSE-2.0
 *  + *
 *  + * Unless required by applicable law or agreed to in writing,
 *  + * software distributed under the License is distributed on an
 *  + * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  + * KIND, either express or implied.  See the License for the
 *  + * specific language governing permissions and limitations
 *  + * under the License.
 *  + ******************************************************************************
 */

package reciter.pubmed.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PubMedQuery {

    private static SimpleDateFormat dt = new SimpleDateFormat("yyyy/MM/dd");

    @JsonProperty("author")
    private String author;

    @JsonProperty("start")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy/MM/dd", timezone = "America/New_York")
    private Date start;

    @JsonProperty("end")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy/MM/dd", timezone = "America/New_York")
    private Date end;

    @JsonProperty("strategy-query")
    private String strategyQuery;

    @JsonProperty("doi")
    private String doi;

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (author != null) {
            //parts.add(author + " [au]");
        	parts.add(author);
        }
        //Added both [DP] and [EDAT] for better capture of pubs : Date of publication - Date added to Entrez
        if (start != null && end != null) {
            parts.add("((" + dt.format(start) + ":" + dt.format(end) + "[EDAT]" + ") OR (" + dt.format(start) + ":" + dt.format(end) + "[DP]))");
        }
        if (strategyQuery != null && !strategyQuery.isEmpty()) {
            parts.add(strategyQuery);
        }
        if (doi != null) {
            parts.add(doi);
        }
        return StringUtils.join(parts, " AND ");
    }
}
