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

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import reciter.model.pubmed.*;

import java.util.ArrayList;
import java.util.List;

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
    private boolean bJournalTitle;
    private boolean bJournalISOAbbreviation;
    private boolean bArticleTitle;
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

    private List<PubMedArticle> pubmedArticles;
    private PubMedArticle pubmedArticle;
    private StringBuilder chars = new StringBuilder();

    public List<PubMedArticle> getPubmedArticles() {
        return pubmedArticles;
    }

    private MedlineCitationYNEnum getMedlineCitationYNEnum(Attributes attributes) {
        String majorTopicYN = attributes.getValue("MajorTopicYN");
        MedlineCitationYNEnum medlineCitationYNEnum = null;
        if (majorTopicYN != null) {
            if ("Y".equals(majorTopicYN))
                medlineCitationYNEnum = MedlineCitationYNEnum.Y;
            else if ("N".equals(majorTopicYN))
                medlineCitationYNEnum = MedlineCitationYNEnum.N;
        }
        return medlineCitationYNEnum;
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

        if (qName.equalsIgnoreCase("ArticleTitle")) {
            bArticleTitle = true;
        }

        if (qName.equalsIgnoreCase("ELocationID")) {
            pubmedArticle.getMedlinecitation().getArticle().setElocationid(MedlineCitationArticleELocationID.builder().build());
            bELocationID = true;
        }

        if (qName.equalsIgnoreCase("Journal")) {
            pubmedArticle.getMedlinecitation().getArticle().setJournal(MedlineCitationJournal.builder().build()); // add journal information.
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

        // <MedlineDate> tag.
        if (bPubDate && qName.equalsIgnoreCase("MedlineDate")) {
            bMedlineDate = true;
        }

        if (qName.equalsIgnoreCase("ISOAbbreviation")) {
            bJournalISOAbbreviation = true;
        }

        if (qName.equalsIgnoreCase("Title")) {
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

        if (qName.equalsIgnoreCase("AuthorList")) {
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

        // not used.
        //		if (qName.equalsIgnoreCase("RefSource") && bCommentsCorrections) {
        //			bCommentsCorrectionsRefSource = true;
        //		}
        if (qName.equalsIgnoreCase("PMID") && bCommentsCorrections) {
            //			bCommentsCorrectionsPmidVersion = true;
            bCommentsCorrectionsPmid = true;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {

        // PMID
        if (bMedlineCitation && bPMID) {
            long pmid = Long.valueOf(chars.toString());
            pubmedArticle.getMedlinecitation().setMedlinecitationpmid(MedlineCitationPMID.builder().pmid(pmid).build());
            bPMID = false;
            bMedlineCitation = false;
        }

        // Article title.
        if (bArticle && bArticleTitle) {
            String articleTitle = chars.toString();
            pubmedArticle.getMedlinecitation().getArticle().setArticletitle(articleTitle); // set the title of the Article.
            bArticleTitle = false;
        }

        if (bELocationID) {
            String eLocationId = chars.toString();
            pubmedArticle.getMedlinecitation().getArticle().getElocationid().setElocationid(eLocationId);
            bELocationID = false;
        }

        // Author last name.
        if (bAuthorLastName) {
            String authorLastName = chars.toString();
            int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
            pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setLastname(authorLastName);
            bAuthorLastName = false;
        }

        // Author fore name.
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
            String affiliation = chars.toString();
            int lastInsertedIndex = pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().size() - 1;
            pubmedArticle.getMedlinecitation().getArticle().getAuthorlist().get(lastInsertedIndex).setAffiliation(affiliation);
            bAffiliation = false;
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
        if (bJournalTitle) {
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
            bPubDate = false;
            bPubDateYear = false;
        }

        // Journal MedlineDate.
        if (bPubDate && bMedlineDate) {
            String pubDateYear = chars.toString();
            if (pubDateYear.length() > 4) {
                pubDateYear = pubDateYear.substring(0, 4); // PMID = 23849565 <MedlineDate>2013 May-Jun</MedlineDate>
            }
            pubmedArticle.getMedlinecitation().getArticle().getJournal().getJournalissue().getPubdate().setYear(pubDateYear);
            bPubDate = false;
            bMedlineDate = false;
        }

        if (bPagination && bMedlinePgn) {
            String pagination = chars.toString();
            pubmedArticle.getMedlinecitation().getArticle().getPagination().getMedlinepgns().add(pagination);
            bMedlinePgn = false;
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

        if (qName.equalsIgnoreCase("CommentsCorrections") && bCommentsCorrectionsList) {
            bCommentsCorrections = false;
        }

        if (qName.equalsIgnoreCase("CommentsCorrectionsList")) {
            bCommentsCorrectionsList = false;
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

        if (bVolume) {
            chars.append(ch, start, length);
        }

        if (bIssue) {
            chars.append(ch, start, length);
        }

        if (bJournalTitle) {
            chars.append(ch, start, length);
        }

        if (bJournalISOAbbreviation) {
            chars.append(ch, start, length);
        }

        if (bPubDate && bPubDateYear) {
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
            chars.append(ch, start, length);
        }

        if (bCommentsCorrections && bCommentsCorrectionsPmid) {
            chars.append(ch, start, length);
        }
    }
}