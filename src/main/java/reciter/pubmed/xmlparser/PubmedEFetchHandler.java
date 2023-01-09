/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package reciter.pubmed.xmlparser;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import reciter.model.pubmed.ArticleIdList;
import reciter.model.pubmed.History;
import reciter.model.pubmed.MedlineCitation;
import reciter.model.pubmed.MedlineCitationArticle;
import reciter.model.pubmed.MedlineCitationArticleAbstract;
import reciter.model.pubmed.MedlineCitationArticleAbstractText;
import reciter.model.pubmed.MedlineCitationArticleAuthor;
import reciter.model.pubmed.MedlineCitationArticleELocationID;
import reciter.model.pubmed.MedlineCitationArticlePagination;
import reciter.model.pubmed.MedlineCitationCommentsCorrections;
import reciter.model.pubmed.MedlineCitationDate;
import reciter.model.pubmed.MedlineCitationGrant;
import reciter.model.pubmed.MedlineCitationJournal;
import reciter.model.pubmed.MedlineCitationJournalISSN;
import reciter.model.pubmed.MedlineCitationJournalIssue;
import reciter.model.pubmed.MedlineCitationKeyword;
import reciter.model.pubmed.MedlineCitationKeywordList;
import reciter.model.pubmed.MedlineCitationMeshHeading;
import reciter.model.pubmed.MedlineCitationMeshHeadingDescriptorName;
import reciter.model.pubmed.MedlineCitationMeshHeadingQualifierName;
import reciter.model.pubmed.MedlineCitationPMID;
import reciter.model.pubmed.MedlineCitationPublicationType;
import reciter.model.pubmed.MedlineCitationYNEnum;
import reciter.model.pubmed.PubMedArticle;
import reciter.model.pubmed.PubMedData;
import reciter.model.pubmed.PubMedPubDate;

/**
 * A SAX handler that parses PubMed XML content.
 *
 * @author jil3004
 */
public class PubmedEFetchHandler extends DefaultHandler {

    private boolean bPubmedArticleSet;
    private boolean bPubmedArticle;
    private boolean bMedlineCitation;
    private boolean bPMID;
    private boolean bPMCID;
    private boolean bDateCreated;
    private boolean bDateCreatedYear;
    private boolean bDateCreatedMonth;
    private boolean bDateCreatedDay;
    private boolean bDateCompleted;
    private boolean bDateCompletedYear;
    private boolean bDateCompletedMonth;
    private boolean bDateCompletedDay;
    private boolean bArticle;
    private boolean bJournal;
    private boolean bISSN;
    private boolean bJournalIssue;
    private boolean bVolume;
    private boolean bIssue;
    private boolean bPubDate;
    private boolean bMedlineDate;
    private boolean bPubDateYear;
    private boolean bPubDateMonth;
    private boolean bPubDateDay;
    private boolean bJournalTitle;
    private boolean bJournalISOAbbreviation;
    private boolean bArticleTitle;
    private boolean bArticleDate;
    private boolean bArticleDateYear;
    private boolean bArticleDateMonth;
    private boolean bArticleDateDay;
    private boolean bPagination;
    private boolean bMedlinePgn;
    private boolean bELocationID;
    private boolean bAbstract;
    private boolean bAbstractText;
    private boolean bCopyrightInformation;
    private boolean bAuthorList;
    private boolean bAuthor;
    private boolean bAuthorLastName;
    private boolean bAuthorForeName;
    private boolean bAuthorInitials;
    private boolean bAffiliationInfo;
    private boolean bAffiliation;
    private boolean bOrcid;
    private boolean bPublicationTypeList;
    private boolean bPublicationType;
    private boolean bMedlineJournalInfo;
    private boolean bCountry;
    private boolean bMedlineTA;
    private boolean bNlmUniqueID;
    private boolean bISSNLinking;
    private boolean bCitationSubset;
    private boolean bMeshHeadingList;
    private boolean bMeshHeading;
    private boolean bDescriptorName;
    private boolean bMajorTopicYN;
    private boolean bQualifierName;
    private boolean bKeywordList;
    private boolean bKeyword;
    private boolean bPubmedData;
    private boolean bHistory;
    private boolean bPubMedPubDate;
    private boolean bPubMedPubDateYear;
    private boolean bPubMedPubDateMonth;
    private boolean bPubMedPubDateDay;
    private boolean bPubMedPubDateHour;
    private boolean bPubMedPubDateMinute;
    private boolean bPublicationStatus;
    private boolean bArticleIdList;
    private boolean bArticleId;
    private boolean bArticleIdPubMed;
    private boolean bArticleIdPii;
    private boolean bArticleIdDoi;
    private boolean bArticleIdPmc;
    private boolean bGrantList;
    private boolean bGrant;
    private boolean bGrantId;
    private boolean bGrantAcronym;
    private boolean bGrantAgency;
    private boolean bGrantCountry;
    private boolean bCommentsCorrectionsList;
    private boolean bCommentsCorrections;
    private boolean bCommentsCorrectionsRefType;
    private boolean bCommentsCorrectionsRefSource;
    private boolean bCommentsCorrectionsPmidVersion;
    private boolean bCommentsCorrectionsPmid;
    private boolean bReferenceList;
    private boolean bReference;
    private boolean bReferenceArticleIdList;
    private boolean bReferenceArticleId;

    private List<PubMedArticle> pubmedArticles;
    private PubMedArticle pubmedArticle;
    private StringBuilder chars = new StringBuilder();
    
    public List<PubMedArticle> getPubmedArticles() {
        return pubmedArticles;
    }

    private MedlineCitationYNEnum getMedlineCitationYNEnum(Attributes attributes) {
        String majorTopicYN = attributes.getValue("MajorTopicYN");
        return new MedlineCitationYNEnum(majorTopicYN);
    }
    
    private String getIssnType(Attributes attributes) {
    	String issnType = attributes.getValue("IssnType");
    	if(issnType.equalsIgnoreCase("Print")) {
    		return "Print";
    	} else {
    		return "Electronic";
    	}
    }

    private String getReferenceArticleIdType(Attributes attributes) {
        String articleIdType = attributes.getValue("IdType");
        return articleIdType;
    }
    
    private String getPubStatus(Attributes attributes) {
    	String pubStatus = attributes.getValue("PubStatus");
    	return pubStatus;
    }
    
    private String getAbstractTextLabel(Attributes attributes) {
    	String abstractTextLabel = attributes.getValue("Label");
    	return abstractTextLabel;
    }
    
    private String getAbstractTextNlmCategory(Attributes attributes) {
    	String abstractTextNlmCategory = attributes.getValue("NlmCategory");
    	return abstractTextNlmCategory;
    }

    private boolean isOrcid(Attributes attributes) {
        return attributes.getValue("Source").equalsIgnoreCase("ORCID");
    }
    /**
     * Pull out year. Year is the first four consecutive numbers in the string.
	 * Attempt to pull out month. (This won't always work.) Month is the first three consecutive letters. Map these letters to a two-digit month equivalent, e.g., "Feb" --> "02", "Oct" --> "10"
	 * Use "01" for day. Don't bother trying to parse that.
	 * PMID(23849565) OR PMID(29756752)
     * @param medlineDate MedlineDate tag value
     * @param medlineCitationDate
     */
    private void getStandardizedDate(String medlineDate, MedlineCitationDate medlineCitationDate) {
    	if(medlineDate != null && medlineDate.length() >=4) {
	    	String year = null;
	    	String month = null;
	    	Pattern pattern = Pattern.compile("\\b\\d{4}\\b");
			Matcher matcher = pattern.matcher(medlineDate);
			if(matcher.find()) {
				year = matcher.group();
			}
			
			//Trying to retrieve month
			
			pattern = Pattern.compile("\\b[a-zA-Z]{3}\\b", Pattern.CASE_INSENSITIVE);
			matcher = pattern.matcher(medlineDate);
			
			if(matcher.find()) {
				month = matcher.group();
				DateTimeFormatter parser = DateTimeFormatter.ofPattern("MMM").withLocale(Locale.ENGLISH);
				TemporalAccessor accessor = parser.parse(month);
				int monthNumber = accessor.get(ChronoField.MONTH_OF_YEAR);
				if(monthNumber != 0 && monthNumber < 10) {
					month = "0" + String.valueOf(monthNumber);
				} else {
					month = String.valueOf(monthNumber);
				}
				
			}
			if(month == null) {
				month = "01";
			}
			medlineCitationDate.setYear(year);
			medlineCitationDate.setMonth(month);
			medlineCitationDate.setDay("01");
    	}
    }

    /**
     * Get the IdType value of ArticleId tag.
     * Example:
     * <ArticleId IdType="pubmed">20634481</ArticleId>
     *
     * @param attributes
     * @return IdType value
     */
    private String getArticleIdType(Attributes attributes) {
        return attributes.getValue("IdType");
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        chars.setLength(0);

        if (qName.equalsIgnoreCase("PubmedArticleSet")) {
            pubmedArticles = new ArrayList<>(); // create a new list of PubmedArticle.
        }
        if (qName.equalsIgnoreCase("PubmedArticle")) {
            pubmedArticle = PubMedArticle.builder().build(); // create a new PubmedArticle.
        }
        //This check was introduced for articles which are of book type returning  <PubmedBookArticle> tag
        if (pubmedArticle != null) {
            if (qName.equalsIgnoreCase("MedlineCitation")) {
                pubmedArticle.setMedlinecitation(MedlineCitation.builder().build()); // set the PubmedArticle's MedlineCitation.
                bMedlineCitation = true;
            }
            if (qName.equalsIgnoreCase("PMID") && !bCommentsCorrectionsList) {
                // CommentsCorrectionsList tag also has pmid.
                bPMID = true;
            }

            if (qName.equalsIgnoreCase("Article")) {
                pubmedArticle.getMedlinecitation().setArticle(MedlineCitationArticle.builder().build()); // set the PubmedArticle's MedlineCitation's MedlineCitationArticle.
                bArticle = true;
            }

            if (bArticle && qName.equalsIgnoreCase("ArticleTitle")) {
                bArticleTitle = true;
            }

            if (qName.equalsIgnoreCase("ELocationID") && attributes.getValue("EIdType").equalsIgnoreCase("doi")) {
                pubmedArticle.getMedlinecitation().getArticle().setElocationid(MedlineCitationArticleELocationID.builder().build());
                bELocationID = true;
            }

            if (qName.equalsIgnoreCase("Journal")) {
                pubmedArticle.getMedlinecitation().getArticle().setJournal(MedlineCitationJournal.builder().build()); // add journal information.
            }
            
            if (qName.equalsIgnoreCase("ISSN")) {
                pubmedArticle.getMedlinecitation().getArticle().getJournal().setIssn(new ArrayList<MedlineCitationJournalISSN>());
                MedlineCitationJournalISSN journalIssn = MedlineCitationJournalISSN.builder().issntype(getIssnType(attributes)).build();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().add(journalIssn);
                bISSN = true;
            }
            
            if(qName.equalsIgnoreCase("ISSNLinking")) {
            	if(pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn() == null) {
            		pubmedArticle.getMedlinecitation().getArticle().getJournal().setIssn(new ArrayList<MedlineCitationJournalISSN>());
            	}
            	MedlineCitationJournalISSN journalLIssn = MedlineCitationJournalISSN.builder().issntype("Linking").build();
            	pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().add(journalLIssn);
            	bISSNLinking = true;
            	
            }

            if (qName.equalsIgnoreCase("JournalIssue")) {
                pubmedArticle.getMedlinecitation().getArticle().getJournal().setJournalissue(MedlineCitationJournalIssue.builder().build());
            }

            if (qName.equalsIgnoreCase("Issue")) {
                bIssue = true;
            }

            if (qName.equalsIgnoreCase("Volume")) {
                bVolume = true;
            }

            // PubMed XML has either <Year>, <Month>, <Day> tags or <MedlineDate> tag.
            if (qName.equalsIgnoreCase("PubDate")) {
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().setPubdate(MedlineCitationDate.builder().build());
                bPubDate = true;
            }

            // <Year> tag.
            if (bPubDate && qName.equalsIgnoreCase("Year")) {
                bPubDateYear = true;
            }
            
            // <Month> tag.
            if (bPubDate && qName.equalsIgnoreCase("Month")) {
                bPubDateMonth = true;
            }
            
            // <Day> tag.
            if (bPubDate && qName.equalsIgnoreCase("Day")) {
                bPubDateDay = true;
            }

            // <MedlineDate> tag.
            if (bPubDate && qName.equalsIgnoreCase("MedlineDate")) {
                bMedlineDate = true;
            }

            if (qName.equalsIgnoreCase("ISOAbbreviation")) {
                bJournalISOAbbreviation = true;
            }

            if (bArticle && qName.equalsIgnoreCase("Title")) {
                bJournalTitle = true;
            }

            if (qName.equalsIgnoreCase("Pagination")) {
                pubmedArticle.getMedlinecitation().getArticle().setPagination(
                        MedlineCitationArticlePagination
                                .builder()
                                .medlinepgns(new ArrayList<>()).build());
                bPagination = true;
            }

            if (qName.equalsIgnoreCase("MedlinePgn")) {
                bMedlinePgn = true;
            }

            if (qName.equalsIgnoreCase("AuthorList") &&
                    pubmedArticle != null) {
                pubmedArticle.getMedlinecitation().getArticle().setAuthorlist(new ArrayList<>()); // set the PubmedArticle's MedlineCitation's MedlineCitationArticle's title.
                bAuthorList = true;
            }
            if (qName.equalsIgnoreCase("Author")) {
                MedlineCitationArticleAuthor author = MedlineCitationArticleAuthor.builder().build();
                pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().add(author); // add author to author list.
                bAuthor = true;
            }
            if (qName.equalsIgnoreCase("LastName") && bAuthorList) {
                bAuthorLastName = true;
            }
            if (qName.equalsIgnoreCase("ForeName") && bAuthorList) {
                bAuthorForeName = true;
            }
            if (qName.equalsIgnoreCase("Initials") && bAuthorList) {
                bAuthorInitials = true;
            }
            if (qName.equalsIgnoreCase("Affiliation")) {
                bAffiliation = true;
            }
            if(qName.equalsIgnoreCase("Identifier") && bAuthorList && isOrcid(attributes)) {
                bOrcid = true;
            }
            if (qName.equalsIgnoreCase("AuthorList") &&
                    pubmedArticle != null) {
                pubmedArticle.getMedlinecitation().getArticle().setAuthorlist(new ArrayList<>()); // set the PubmedArticle's MedlineCitation's MedlineCitationArticle's title.
                bAuthorList = true;
            }
            if (qName.equalsIgnoreCase("Abstract") &&
                    pubmedArticle != null) {
            	pubmedArticle.getMedlinecitation().getArticle().setPublicationAbstract(MedlineCitationArticleAbstract.builder().abstractTexts(new ArrayList<>()).build());
                bAbstract = true;
            }
            if (qName.equalsIgnoreCase("AbstractText") && bAbstract) {
            	MedlineCitationArticleAbstractText abstractText = MedlineCitationArticleAbstractText.builder().abstractTextLabel(getAbstractTextLabel(attributes)).abstractTextNlmCategory(getAbstractTextNlmCategory(attributes)).build();
            	pubmedArticle.getMedlinecitation().getArticle().getPublicationAbstract().getAbstractTexts().add(abstractText);
                bAbstractText = true;
            }
            if (qName.equalsIgnoreCase("CopyrightInformation") && bAbstract) {
                bCopyrightInformation = true;
            }
            if (qName.equalsIgnoreCase("PublicationTypeList") &&
                    pubmedArticle != null) {
                pubmedArticle.getMedlinecitation().getArticle().setPublicationtypelist(new ArrayList<>()); // set the PubmedArticle's MedlineCitation's MedlineCitationArticle's title.
                bPublicationTypeList = true;
            }
            if (qName.equalsIgnoreCase("PublicationType") && bPublicationTypeList) {
                bPublicationType = true;
            }
            
            if (qName.equalsIgnoreCase("KeywordList")) {
                pubmedArticle.getMedlinecitation().setKeywordlist(
                        MedlineCitationKeywordList
                                .builder()
                                .keywordlist(new ArrayList<>())
                                .build()); // add keyword information.
                bKeywordList = true;
            }
            if (qName.equalsIgnoreCase("Keyword")) {
                bKeyword = true;
            }
            if (qName.equalsIgnoreCase("MeshHeadingList")) {
                pubmedArticle.getMedlinecitation().setMeshheadinglist(new ArrayList<>());
            }
            if (qName.equalsIgnoreCase("DescriptorName")) {
                // Set MedlineCitationYNEnum.
                MedlineCitationYNEnum medlineCitationYNEnum = getMedlineCitationYNEnum(attributes);
                MedlineCitationMeshHeading medlineCitationMeshHeading = MedlineCitationMeshHeading.builder().build();

                // Set DescriptorName.
                MedlineCitationMeshHeadingDescriptorName medlineCitationMeshHeadingDescriptorName =
                        MedlineCitationMeshHeadingDescriptorName
                                .builder()
                                .majortopicyn(medlineCitationYNEnum)
                                .build();
                medlineCitationMeshHeading.setDescriptorname(medlineCitationMeshHeadingDescriptorName);

                // Set QualifierName list.
                medlineCitationMeshHeading.setQualifiernamelist(new ArrayList<>());

                // Add to list of MeshHeading.
                pubmedArticle.getMedlinecitation().getMeshheadinglist().add(medlineCitationMeshHeading);
                bDescriptorName = true;
            }
            if (qName.equalsIgnoreCase("QualifierName")) {
                MedlineCitationYNEnum medlineCitationYNEnum = getMedlineCitationYNEnum(attributes);

                // Get the last inserted list of qualifier names.
                int size = pubmedArticle.getMedlinecitation().getMeshheadinglist().size();
                List<MedlineCitationMeshHeadingQualifierName> medlineCitationMeshHeadingQualifierNames =
                        pubmedArticle.getMedlinecitation().getMeshheadinglist().get(size - 1).getQualifiernamelist();

                // Create a new MedlineCitationMeshHeadingQualifierName.
                MedlineCitationMeshHeadingQualifierName qualifierName =
                        MedlineCitationMeshHeadingQualifierName
                                .builder()
                                .majortopicyn(medlineCitationYNEnum)
                                .build();

                // Insert into list of MedlineCitationMeshHeadingQualifierNames.
                medlineCitationMeshHeadingQualifierNames.add(qualifierName);

                bQualifierName = true;
            }
            if (qName.equalsIgnoreCase("GrantList")) {
                pubmedArticle.getMedlinecitation().getArticle().setGrantlist(new ArrayList<>());
                bGrantList = true;
            }
            if (qName.equalsIgnoreCase("Grant")) {
                MedlineCitationGrant grant = MedlineCitationGrant.builder().build();
                pubmedArticle.getMedlinecitation().getArticle().getGrantlist().add(grant);
                bGrant = true;
            }
            if (qName.equalsIgnoreCase("GrantID")) {
                bGrantId = true;
            }
            if (qName.equalsIgnoreCase("Acronym")) {
                bGrantAcronym = true;
            }
            if (qName.equalsIgnoreCase("Agency")) {
                bGrantAgency = true;
            }
            if (qName.equalsIgnoreCase("Country")) {
                bGrantCountry = true;
            }
            
            if (qName.equalsIgnoreCase("CommentsCorrectionsList")) {
                List<MedlineCitationCommentsCorrections> medlineCitationCommentsCorrections = new ArrayList<>();
                pubmedArticle.getMedlinecitation().setCommentscorrectionslist(medlineCitationCommentsCorrections);
                bCommentsCorrectionsList = true;
            }
            
            if (qName.equalsIgnoreCase("CommentsCorrections") && bCommentsCorrectionsList) {
                bCommentsCorrections = true;
            }

            if (qName.equalsIgnoreCase("ReferenceList")) {
                bReferenceList = true;
            }
            
            if (qName.equalsIgnoreCase("Reference") && bReferenceList) {
                if(pubmedArticle.getMedlinecitation().getCommentscorrectionslist() == null) {
                    List<MedlineCitationCommentsCorrections> medlineCitationCommentsCorrections = new ArrayList<>();
                    pubmedArticle.getMedlinecitation().setCommentscorrectionslist(medlineCitationCommentsCorrections);
                }
                bReference = true;
            }

            if (qName.equalsIgnoreCase("ArticleIdList") && bReference) {
                bReferenceArticleIdList = true;
            }
            
            if(qName.equalsIgnoreCase("ArticleDate")) {
            	bArticleDate = true;
            	pubmedArticle.getMedlinecitation().getArticle().setArticledate(MedlineCitationDate.builder().build());
            }
            
            if(bArticleDate && qName.equalsIgnoreCase("Year")) {
            	bArticleDateYear = true;
            }
            
            if(bArticleDate && qName.equalsIgnoreCase("Month")) {
            	bArticleDateMonth = true;
            }
            
            if(bArticleDate && qName.equalsIgnoreCase("Day")) {
            	bArticleDateDay = true;
            }
            

            // not used.
            //		if (qName.equalsIgnoreCase("RefSource") && bCommentsCorrections) {
            //			bCommentsCorrectionsRefSource = true;
            //		}
            if (qName.equalsIgnoreCase("PMID") && bCommentsCorrections) {
                //			bCommentsCorrectionsPmidVersion = true;
                bCommentsCorrectionsPmid = true;
            }

            if (qName.equalsIgnoreCase("ArticleId") && bReferenceArticleIdList) {
                if(getReferenceArticleIdType(attributes).equalsIgnoreCase("pubmed")) {
                    bReferenceArticleId = true;
                    MedlineCitationCommentsCorrections medlineCitationCommentsCorrections = MedlineCitationCommentsCorrections.builder().build();
                    pubmedArticle.getMedlinecitation().getCommentscorrectionslist().add(medlineCitationCommentsCorrections);
                }
            }

            if (qName.equalsIgnoreCase("PubmedData")) {
                bPubmedData = true;
                pubmedArticle.setPubmeddata(new PubMedData());
            }
            
            if(bPubmedData && qName.equalsIgnoreCase("History")) {
            	bHistory = true;
            	History history = History.builder().pubmedPubDate(new ArrayList<PubMedPubDate>()).build();
            	pubmedArticle.getPubmeddata().setHistory(history);
            }
            
            if(qName.equalsIgnoreCase("PubMedPubDate")) {
            	bPubMedPubDate = true;
            	MedlineCitationDate medlineCitationDate = MedlineCitationDate.builder().build();
            	PubMedPubDate pubmedPubDate = PubMedPubDate.builder().pubMedPubDate(medlineCitationDate).pubStatus(getPubStatus(attributes)).build();
            	pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().add(pubmedPubDate);
            }
            
            if(bPubMedPubDate && qName.equalsIgnoreCase("Year")) {
            	bPubMedPubDateYear = true;
            }
            
            if(bPubMedPubDate && qName.equalsIgnoreCase("Month")) {
            	bPubMedPubDateMonth = true;
            }
            
            if(bPubMedPubDate && qName.equalsIgnoreCase("Day")) {
            	bPubMedPubDateDay = true;
            }

            if (qName.equalsIgnoreCase("ArticleIdList")) {
                bArticleIdList = true;
            }

            if (qName.equalsIgnoreCase("ArticleId") && !bReferenceArticleIdList && !bReferenceArticleId) {  
                bArticleId = true;
                String idType = getArticleIdType(attributes);
                if ("pubmed".equals(idType)) {
                    bArticleIdPubMed = true;
                } else if ("pii".equals(idType)) {
                    bArticleIdPii = true;
                } else if ("doi".equals(idType)) {
                    bArticleIdDoi = true;
                } else if ("pmc".equals(idType)) {
                	pubmedArticle.getPubmeddata().setArticleIdList(new ArticleIdList());
                    bArticleIdPmc = true;
                }
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        //This check was introduced for articles which are of book type returning  <PubmedBookArticle> tag
        if (pubmedArticle != null) {
            // PMID
            if (bMedlineCitation && bPMID) {
                long pmid = Long.valueOf(chars.toString());
                pubmedArticle.getMedlinecitation().setMedlinecitationpmid(MedlineCitationPMID.builder().pmid(pmid).build());
                bPMID = false;
                bMedlineCitation = false;
            }

            // Article title.
            if (bArticle && bArticleTitle) {

                // Replace new line breaks and any two or more whitespaces with single whitespace                
                String articleTitle = chars.toString().replaceAll("\\R+\\s{2,}", " ").trim(); 

                // Substitute certain non-printable, hexadecimal characters for a space
                articleTitle = articleTitle.replaceAll("[ | | | | | | ]", " ");                

                // Delete certain non-printable, hexadecimal characters
                articleTitle = articleTitle.replaceAll("[ || ]", "");         

                // Set the title of the article.
                pubmedArticle.getMedlinecitation().getArticle().setArticletitle(articleTitle); 
                bArticleTitle = false;
            }

            if (bELocationID) {
                String eLocationId = chars.toString().trim();
                if(!eLocationId.contains(" ")) { //Case where doi would have a space in between doi which causes exception when retrieving scopus articles using doi - pmid - 24763504
                	pubmedArticle.getMedlinecitation().getArticle().getElocationid().setElocationid(eLocationId);
                }
                bELocationID = false;
            }

            // Author last name.
            if (bAuthorLastName) {
              String authorLastName = chars.toString();
            
              int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
              pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setLastname(authorLastName);
              bAuthorLastName = false;
            }

            // Author forename.
            if (bAuthorForeName) {
              String authorForeName = chars.toString();
                        
              int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
              pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setForename(authorForeName);
              bAuthorForeName = false;
            }

            // Author middle initials.
            if (bAuthorInitials) {
                String authorInitials = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setInitials(authorInitials);
                bAuthorInitials = false;
            }

            // Author affiliations.
            if (bAffiliation) {

                // Replace new line breaks and any two or more whitespaces with single whitespace                
                String affiliation = chars.toString().replaceAll("\\R+\\s{2,}", " ").trim(); 

                // Substitute certain non-printable, hexadecimal characters for a space
                affiliation = affiliation.replaceAll("[ | | | | | | ]", " ");                

                // Delete certain non-printable, hexadecimal characters
                affiliation = affiliation.replaceAll("[ || ]", "");      

                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setAffiliation(affiliation);
                bAffiliation = false;
            }    
            
            // Author ORCID identifier
            if (bOrcid) {
                String orcid = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setOrcid(orcid);
                bOrcid = false;
            }
            
            if(bISSN) {
            	String issn = chars.toString();
            	int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().size() - 1;
            	pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().get(lastInsertedIndex).setIssn(issn);
            	bISSN = false;
            }
            
            if(bISSNLinking) {
            	String lissn = chars.toString();
            	int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().size() - 1;
            	pubmedArticle.getMedlinecitation().getArticle().getJournal().getIssn().get(lastInsertedIndex).setIssn(lissn);
            	bISSNLinking = false;
            }

            // Journal Volume
            if (bVolume) {
                String volume = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().setVolume(volume);
                bVolume = false;
            }

            // Journal issue
            if (bIssue) {
                String issue = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().setIssue(issue);
                bIssue = false;
            }

            // Journal title
            if (bArticle && bJournalTitle) {
              String journalTitle = chars.toString();
            
              pubmedArticle.getMedlinecitation().getArticle().getJournal().setTitle(journalTitle);
              bJournalTitle = false;
            }

            // Journal ISO abbreviation.
            if (bJournalISOAbbreviation) {
                String isoAbbr = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().setIsoAbbreviation(isoAbbr);
                bJournalISOAbbreviation = false;
            }

            // Journal Year.
            if (bPubDate && bPubDateYear) {
                String pubDateYear = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate().setYear(pubDateYear);
                //bPubDate = false;
                bPubDateYear = false;
            }
            
            if (bPubDate && bPubDateMonth) {
                String pubDateMonth = chars.toString();
                if(pubDateMonth.trim().length() == 3) {
                	DateTimeFormatter parser = DateTimeFormatter.ofPattern("MMM").withLocale(Locale.ENGLISH);
        			TemporalAccessor accessor = parser.parse(pubDateMonth);
        			int monthNumber = accessor.get(ChronoField.MONTH_OF_YEAR);
    				if(monthNumber != 0 && monthNumber < 10 ) {
    					pubDateMonth = "0" + String.valueOf(monthNumber);
    				} else {
    					pubDateMonth = String.valueOf(monthNumber);
    				}
                }
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate().setMonth(pubDateMonth);
                //bPubDate = false;
                bPubDateMonth = false;
            }
            
            if (bPubDate && bPubDateDay) {
                String pubDateDay = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate().setDay(pubDateDay);
                //bPubDate = false;
                bPubDateDay = false;
            }
            

            // Journal MedlineDate.
            if (bPubDate && bMedlineDate) {
                String medlineDate = chars.toString();
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().setMedlineDate(medlineDate);
                MedlineCitationDate medlineCitationDate = pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate();
                getStandardizedDate(medlineDate, medlineCitationDate);
                
                /*if (pubDateYear.length() > 4) {
                    pubDateYear = pubDateYear.substring(0, 4); // PMID = 23849565 <MedlineDate>2013 May-Jun</MedlineDate>
                }
                pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate().setYear(pubDateYear);*/
                bPubDate = false;
                bMedlineDate = false;
            }
            
            if(qName.equalsIgnoreCase("PubDate")) {
            	bPubDate = false;
            }

            if (bPagination && bMedlinePgn) {
                String pagination = chars.toString();
                if(pagination != null 
                		&&
                		!pagination.isEmpty()) {
                	pubmedArticle.getMedlinecitation().getArticle().getPagination().getMedlinepgns().add(pagination);
                }
                bMedlinePgn = false;
            }
            
            //Publication Type
            if (bPublicationTypeList && bPublicationType) {
                MedlineCitationPublicationType publicationType = MedlineCitationPublicationType.builder().build();
                publicationType.setPublicationtype(chars.toString());
                pubmedArticle.getMedlinecitation().getArticle().getPublicationtypelist().add(publicationType);
                bPublicationType = false;
            }
            
            if (qName.equalsIgnoreCase("PublicationTypeList")) {
                bPublicationTypeList = false;
            }

            //Abstract
            
            if (bAbstractText && bAbstract) {
              int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getPublicationAbstract().getAbstractTexts().size() - 1;

                // Replace new line breaks and any two or more whitespaces with single whitespace                
                String publicationAbstractText = chars.toString().replaceAll("\\R+\\s{2,}", " ").trim(); 

                // Substitute certain non-printable, hexadecimal characters for a space
                publicationAbstractText = publicationAbstractText.replaceAll("[ | | | | | | ]", " ");                

                // Delete certain non-printable, hexadecimal characters
                publicationAbstractText = publicationAbstractText.replaceAll("[ || ]", "");   
         
              pubmedArticle.getMedlinecitation().getArticle().getPublicationAbstract().getAbstractTexts().get(lastInsertedIndex).setAbstractText(publicationAbstractText);
              bAbstractText = false;
            }
            
            if (bCopyrightInformation && bAbstract) {
                MedlineCitationArticleAbstract publicationAbstract = pubmedArticle.getMedlinecitation().getArticle().getPublicationAbstract();
                publicationAbstract.setCopyrightInformation(chars.toString());
                //pubmedArticle.getMedlinecitation().getArticle().setPublicationAbstract(publicationAbstract);
                bCopyrightInformation = false;
            }
            
            if (qName.equalsIgnoreCase("Abstract")) {
            	bAbstract = false;
            }
            
            // Keyword.
            if (bKeywordList && bKeyword) {
                MedlineCitationKeyword keyword = MedlineCitationKeyword.builder().build();
                keyword.setKeyword(chars.toString());
                pubmedArticle.getMedlinecitation().getKeywordlist().getKeywordlist().add(keyword);
                bKeyword = false;
            }

            // MeSH descriptor name.
            if (bDescriptorName) {
                String descriptorName = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getMeshheadinglist().size() - 1;
                pubmedArticle.getMedlinecitation().getMeshheadinglist().get(lastInsertedIndex).getDescriptorname().setDescriptorname(descriptorName); // set descriptor name for MeSH.
                bDescriptorName = false;
            }

            // MeSH qualifier name.
            if (bQualifierName) {
                String qualifierName = chars.toString();
                int lastInsertedMeshHeadingIndex = pubmedArticle.getMedlinecitation().getMeshheadinglist().size() - 1;
                MedlineCitationMeshHeading meshHeading = pubmedArticle.getMedlinecitation().getMeshheadinglist().get(lastInsertedMeshHeadingIndex);
                int lastInsertedQualifierNameIndex = meshHeading.getQualifiernamelist().size() - 1;
                MedlineCitationMeshHeadingQualifierName meshHeadingQualifierName =
                        meshHeading.getQualifiernamelist().get(lastInsertedQualifierNameIndex);
                meshHeadingQualifierName.setQualifiername(qualifierName);
                bQualifierName = false;
            }
            
            // End of PubmedArticle tag. Add the PubmedArticle to the pubmedArticleList.
            if (qName.equalsIgnoreCase("PubmedArticle")) {
                pubmedArticles.add(pubmedArticle);
            }
            
            // End of Article tag.
            if (qName.equalsIgnoreCase("Article")) {
                bArticle = false;
            }

            // End of keyword list.
            if (qName.equalsIgnoreCase("KeywordList")) {
                bKeywordList = false;
            }

            if (qName.equalsIgnoreCase("Pagination")) {
                bPagination = false;
            }
            
            // End of GrantID tag.
            if (bGrant && bGrantId) {
                String grantId = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getGrantlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getGrantlist().get(lastInsertedIndex).setGrantid(grantId);
                bGrantId = false;
            }

            if (bGrant && bGrantAcronym) {
                String grantAcronym = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getGrantlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getGrantlist().get(lastInsertedIndex).setAcronym(grantAcronym);
                bGrantAcronym = false;
            }

            if (bGrant && bGrantAgency) {
                String grantAgency = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getGrantlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getGrantlist().get(lastInsertedIndex).setAgency(grantAgency);
                bGrantAgency = false;
            }

            if (bGrant && bGrantCountry) {
                String grantCountry = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getGrantlist().size() - 1;
                pubmedArticle.getMedlinecitation().getArticle().getGrantlist().get(lastInsertedIndex).setCountry(grantCountry);
                bGrantCountry = false;
            }

            if (qName.equalsIgnoreCase("Grant")) {
                bGrant = false;
            }

            if (qName.equalsIgnoreCase("GrantList")) {
                bGrantList = false;
            }

            if (qName.equalsIgnoreCase("AuthorList")) {
                bAuthorList = false;
            }

            if (bCommentsCorrections && bCommentsCorrectionsPmid) {
                MedlineCitationCommentsCorrections medlineCitationCommentsCorrections =
                        MedlineCitationCommentsCorrections
                                .builder()
                                .pmid(chars.toString())
                                .build();
                pubmedArticle.getMedlinecitation().getCommentscorrectionslist().add(medlineCitationCommentsCorrections);
                bCommentsCorrectionsPmid = false;
                bCommentsCorrections = false;
            }

            if (bReferenceArticleId && bReferenceArticleIdList) {
                String articleId = chars.toString();
                int lastInsertedIndex = pubmedArticle.getMedlinecitation().getCommentscorrectionslist().size() - 1;
                pubmedArticle.getMedlinecitation().getCommentscorrectionslist().get(lastInsertedIndex).setPmid(articleId);
                bReferenceArticleId = false;
            }

            if (qName.equalsIgnoreCase("CommentsCorrections") && bCommentsCorrectionsList) {
                bCommentsCorrections = false;
            }

            if (qName.equalsIgnoreCase("ArticleIdList") && bReference) {
                bReferenceArticleIdList = false;
            }

            if (qName.equalsIgnoreCase("CommentsCorrectionsList")) {
                bCommentsCorrectionsList = false;
            }

            if (qName.equalsIgnoreCase("Reference") && bReferenceList) {
                bReference = false;
            }

            if (qName.equalsIgnoreCase("ReferenceList")) {
                bReferenceList = false;
            }

            //End of <ArticleDate> tag
            if(bArticleDate && qName.equalsIgnoreCase("Year")) {
            	String articleDateYear = chars.toString();
            	pubmedArticle.getMedlinecitation().getArticle().getArticledate().setYear(articleDateYear);
            	bArticleDateYear = false;
            }
            
            if(bArticleDate && qName.equalsIgnoreCase("Month")) {
            	String articleDateMonth = chars.toString();
            	pubmedArticle.getMedlinecitation().getArticle().getArticledate().setMonth(articleDateMonth);
            	bArticleDateMonth = false;
            }
            
            if(bArticleDate && qName.equalsIgnoreCase("Day")) {
            	String articleDateDay = chars.toString();
            	pubmedArticle.getMedlinecitation().getArticle().getArticledate().setDay(articleDateDay);
            	bArticleDateDay = false;
            }
            
            if(qName.equalsIgnoreCase("ArticleDate")) {
            	bArticleDate = false;
            }
            
            if(bPubMedPubDate && bPubMedPubDateYear) {
            	String pubmedPubDateYear = chars.toString();
                int lastInsertedIndex = pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().size() - 1;
                pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().get(lastInsertedIndex).getPubMedPubDate().setYear(pubmedPubDateYear);
                bPubMedPubDateYear = false;
            }
            
            if(bPubMedPubDate && bPubMedPubDateMonth) {
            	String pubmedPubDateMonth = chars.toString();
                int lastInsertedIndex = pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().size() - 1;
                pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().get(lastInsertedIndex).getPubMedPubDate().setMonth(pubmedPubDateMonth);
                bPubMedPubDateMonth = false;
            }
            
            if(bPubMedPubDate && bPubMedPubDateDay) {
            	String pubmedPubDateDay = chars.toString();
                int lastInsertedIndex = pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().size() - 1;
                pubmedArticle.getPubmeddata().getHistory().getPubmedPubDate().get(lastInsertedIndex).getPubMedPubDate().setDay(pubmedPubDateDay);
                bPubMedPubDateDay = false;
            }
            
            if(qName.equalsIgnoreCase("PubMedPubDate")) {
            	bPubMedPubDate = false;
            }
            
            if(qName.equalsIgnoreCase("History")) {
            	bHistory = false;
            }

            if (qName.equalsIgnoreCase("PubmedData")) {
                bPubmedData = false;
            }
            
            if (bArticleIdPmc) {
                pubmedArticle.getPubmeddata().getArticleIdList().setPmc(chars.toString());
                bArticleIdPmc = false;
                bArticleId = false;
                bArticleIdList = false;
            }
            
            if (qName.equalsIgnoreCase("ArticleId") && bArticleIdList) {
	            bArticleId = false;
            }
            
            if (qName.equalsIgnoreCase("ArticleIdList")) {
	            bArticleIdList = false;
            }

            /*if (qName.equalsIgnoreCase("ArticleIdList")) {
                bArticleIdList = false;
                bArticleId = false;
                bArticleIdPubMed = false;
                bArticleIdPii = false;
                bArticleIdDoi = false;
            }*/

            
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
    	
        if (bMedlineCitation && bPMID) {
            chars.append(ch, start, length);
        }

        if (bArticle && bArticleTitle) {
        	chars.append(ch, start, length);
        }

        if (bELocationID) {
            chars.append(ch, start, length);
        }

        if (bAuthorLastName) {
            chars.append(ch, start, length);
        }

        if (bAuthorForeName) {
            chars.append(ch, start, length);
        }

        if (bAuthorInitials) {
            chars.append(ch, start, length);
        }

        if (bAffiliation) {
            chars.append(ch, start, length);
        }

        if (bOrcid) {
            chars.append(ch, start, length);
        }

        if (bVolume) {
            chars.append(ch, start, length);
        }
        
        if(bISSN) {
        	chars.append(ch, start, length);
        }
        
        if(bISSNLinking) {
        	chars.append(ch, start, length);
        }

        if (bIssue) {
            chars.append(ch, start, length);
        }

        if (bArticle && bJournalTitle) {
            chars.append(ch, start, length);
        }

        if (bJournalISOAbbreviation) {
            chars.append(ch, start, length);
        }

        if (bPubDate && bPubDateYear) {
            chars.append(ch, start, length);
        }
        
        if (bPubDate && bPubDateMonth) {
            chars.append(ch, start, length);
        }
        
        if (bPubDate && bPubDateDay) {
            chars.append(ch, start, length);
        }

        if (bPubDate && bMedlineDate) {
            chars.append(ch, start, length);
        }

        if (bPagination && bMedlinePgn) {
            chars.append(ch, start, length);
        }

        if (bKeywordList && bKeyword) {
            chars.append(ch, start, length);
        }

        if (bDescriptorName) {
            chars.append(ch, start, length);
        }

        if (bQualifierName) {
            chars.append(ch, start, length);
        }

        if (bGrant && bGrantId) {
            chars.append(ch, start, length);
        }

        if (bGrant && bGrantAcronym) {
            chars.append(ch, start, length);
        }

        if (bGrant && bGrantAgency) {
            chars.append(ch, start, length);
        }

        if (bGrant && bGrantCountry) {
            if (chars.length() == 0) {
                chars.append(ch, start, length);
            }
        }
        
        if(bPublicationTypeList && bPublicationType) {
        	chars.append(ch, start, length);
        }
        
        if(bAbstractText &&  bAbstract) {
        	chars.append(ch, start, length);
        }
        
        if(bCopyrightInformation &&  bAbstract) {
        	chars.append(ch, start, length);
        }
        
        if(bArticleDate && bArticleDateYear) {
        	chars.append(ch, start, length);
        }
        
        if(bArticleDate && bArticleDateMonth) {
        	chars.append(ch, start, length);
        }
        
        if(bArticleDate && bArticleDateDay) {
        	chars.append(ch, start, length);
        }
        
        if (bCommentsCorrections && bCommentsCorrectionsPmid) {
            chars.append(ch, start, length);
        }

        if (bReferenceArticleIdList && bReferenceArticleId) {
            chars.append(ch, start, length);
        }
        
        if(bHistory && bPubMedPubDate && bPubMedPubDateYear) {
        	chars.append(ch, start, length);
        }
        
        if(bHistory && bPubMedPubDate && bPubMedPubDateMonth) {
       	 	chars.append(ch, start, length);
        }
        
        if(bHistory && bPubMedPubDate && bPubMedPubDateDay) {
        	chars.append(ch, start, length);
        }

        if (bArticleIdPmc) {
            chars.append(ch, start, length);
        }
        
        /*if (bPubmedData) {
            chars.append(ch, start, length);
        }*/
    }
}
