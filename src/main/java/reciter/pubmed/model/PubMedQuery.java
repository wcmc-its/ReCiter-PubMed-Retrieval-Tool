/*
 * +/*******************************************************************************
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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class PubMedQuery {

    private static SimpleDateFormat dt = new SimpleDateFormat("yyyy/MM/dd");

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Date getStart() {
        return start;
    }

    public void setStart(Date start) {
        this.start = start;
    }

    public Date getEnd() {
        return end;
    }

    public void setEnd(Date end) {
        this.end = end;
    }

    public String getStrategyQuery() {
        return strategyQuery;
    }

    public void setStrategyQuery(String strategyQuery) {
        this.strategyQuery = strategyQuery;
    }

    @JsonProperty("author")
    private String author;

    @JsonProperty("start")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy/MM/dd")
    private Date start;

    @JsonProperty("end")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy/MM/dd")
    private Date end;

    @JsonProperty("strategy-query")
    private String strategyQuery;

    @JsonProperty("doi")
    private String doi;

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    @Override
    public String toString() {
//        return author + "[au]" + " AND " + dt.format(start) + ":" + dt.format(end) + "[DP]";
        List<String> parts = new ArrayList<String>();
        if (author != null) {
            parts.add(author + " [au]");
        }
        if (start != null && end != null) {
            parts.add(dt.format(start) + ":" + dt.format(end) + "[DP]");
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
