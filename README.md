# ReCiter-PubMed-Retrieval-Tool

![Build Status](https://codebuild.us-east-1.amazonaws.com/badges?uuid=eyJlbmNyeXB0ZWREYXRhIjoiV0w5MExveXNpdzBrL1hRMDlmYjhLNjRFek1NdTVxMk9BOWZEcDdxVENuZXNQS0FGdlZxY3h3Smd1b3ArTVhNTzUvK1pXVlI3N1JkdmRXNiswc1VPcHNjPSIsIml2UGFyYW1ldGVyU3BlYyI6IllneSs4bG9NNmMyeEtWOTkiLCJtYXRlcmlhbFNldFNlcmlhbCI6MX0%3D&branch=master)
![version](https://img.shields.io/badge/version-1.0-blue.svg?maxAge=2592000)
[![codebeat badge](https://codebeat.co/badges/26e88904-3263-47f3-a246-7c65979cca46)](https://codebeat.co/projects/github-com-wcmc-its-reciter-pubmed-retrieval-tool-master)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](http://makeapullrequest.com)
[![Pending Pull-Requests](https://img.shields.io/github/issues-pr-raw/wcmc-its/ReCiter-PubMed-Retrieval-Tool.svg?color=blue)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/pulls?q=is%3Aopen+is%3Apr)
[![Closed Pull-Requests](https://img.shields.io/github/issues-pr-closed-raw/wcmc-its/ReCiter-PubMed-Retrieval-Tool.svg?color=blue)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/pulls?q=is%3Apr+is%3Aclosed)
[![GitHub issues open](https://img.shields.io/github/issues-raw/wcmc-its/ReCiter-PubMed-Retrieval-Tool.svg?maxAge=2592000)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/issues?q=is%3Aopen+is%3Aissue)
[![GitHub issues closed](https://img.shields.io/github/issues-closed-raw/wcmc-its/ReCiter-PubMed-Retrieval-Tool.svg?maxAge=2592000)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/issues?q=is%3Aissue+is%3Aclosed)
[![star this repo](http://githubbadges.com/star.svg?user=wcmc-its&repo=ReCiter-PubMed-Retrieval-Tool&style=flat)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool)
[![fork this repo](http://githubbadges.com/fork.svg?user=wcmc-its&repo=ReCiter-PubMed-Retrieval-Tool&style=flat)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/fork)
[![Tags](https://img.shields.io/github/tag/wcmc-its/ReCiter-PubMed-Retrieval-Tool.svg?style=social)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/releases)
[![Github All Releases](https://img.shields.io/github/downloads/wcmc-its/ReCiter-PubMed-Retrieval-Tool/total.svg)]()
[![Open Source Love](https://badges.frapsoft.com/os/v3/open-source.svg?v=102)](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/) 

NCBI offers [several methods](https://www.ncbi.nlm.nih.gov/pmc/tools/get-metadata/) for accessing its data. None of the methods fully support a RESTful interface returning JSON. The ReCiter PubMed Retrieval Tool is a REST API for retrieving PubMed articles from https://www.ncbi.nlm.nih.gov/pubmed/. You can pass a any PubMed query to the REST API and it will return list of PubMed article objects or a return count of the number of records.

This application was written to work with [ReCiter](https://github.com/wcmc-its/ReCiter/), a tool for disambiguating articles written in PubMed. However, this application can work as a standalone service.

## Advantages over using eFetch API alone

This tool has several advantages over using the eFetch API.
- The eFetch API outputs data as XML while the ReCiter PubMed Retrieval Tool outputs data as JSON, a format which is easier for developers to use
- Even if your machine make calls that don’t exceed the published allowable calls per second, NCBI will inexplicably throttle requests. This application checks the `X-RateLimit-Remaining` and `Retry-After` response headers, and calls the API after the `retry-After` value.


## Prerequisites

- Java 11
- Latest version of Maven. To install Maven navigate to the directory where ReCiter PubMed Retrieval Tool will be installed, execute `brew install maven` and then `mvn clean install`
If you want to use Java 8 then update `<java.version>1.8</java.version>` in [pom.xml](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/f30963755659e5d4cc668297e3c1e7a8d577e259/pom.xml#L20)

It is not necessary to install ReCiter in order to use the API.


## Installing

1. Navigate to directory where you wish to install the application, e.g., `cd ~/Paul/Documents/`
2. Clone the repository: `git clone https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool.git`
3. Navigate to the newly installed folder: `cd ReCiter-PubMed-Retrieval-Tool`
4. Use Maven to build the project: `mvn clean install -Dmaven.test.skip=true`
5. Set the API key. (See "Obtaining an API key" below) 
- Option #1: Command line
  - Enter `export PUBMED_API_KEY=[enter your API key here]`
- Option #2: Enter as an environment variable in AWS itself. If you are deploying to an AWS instance, [add the environment variable](https://docs.aws.amazon.com/elasticbeanstalk/latest/dg/environments-cfg-softwaresettings.html#environments-cfg-softwaresettings-console) in the Elastic Beanstalk configuration section.
- Option #3: In Eclipse application
  - Open Eclipse
  - Right-click on Application.java found here: ReCiter-PubMed-Retrieval-Tool --> src/main/java --> reciter --> Application.java
  - Click on "Run As..." --> "Run Configurations..."
  - Click on "ReCiter-PubMed-Retrieval-Tool" in sidebar
  - Click on "Environment" tab
  - Under variable, add "PUBMED_API_KEY" and enter the API key.
6. Set the desired port
- Option #1: Set at the system level using this command `export SERVER_PORT=[your port number]`. This supersedes any ports set in application.properties.
- Option #2: Update the application.properties file located at `/src/main/resources/` Make sure the port doesn't conflict with other services such as ReCiter or ReCiter PubMed Retrieval Tool.
7. Build Maven instance `mvn spring-boot:run`
8. Visit `http://localhost:[your port number]/swagger-ui/index.html` or  `http://localhost:[your port number]/swagger-ui/` to see the Swagger page for this service.

## Obtaining an API key

As a default, the PubMed Retrieval Tool works without a PubMed API key, however we recommend that you get an API key issued by NCBI. This allows you to make more requests per second.

[These directions](https://ncbiinsights.ncbi.nlm.nih.gov/2017/11/02/new-api-keys-for-the-e-utilities/) show how you can get an API key and use it with this application.



## Using

The PubMed Retrieval Tool supports all syntax that the PubMed interface does, however, it does not return all fields. To see which fields are returned, view the [PubMed Data Model](https://github.com/wcmc-its/ReCiter-PubMed-Model). Note also that the Swagger interface translates your request into the cURL syntax.

### Search using "/pubmed/query/"

1. Go to the Swagger interface for PubMed Retrieval Tool.
2. Click on `/pubmed/query/{query}`
3. Enter your query. 
4. Click execute.

![https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/SearchPubMed.gif](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/SearchPubMed.gif)

#### Return only certain fields

You may also specify which fields you'd like to return. For example: `medlinecitation.medlinecitationpmid.pmid` or `medlinecitation.medlinecitationpmid.pmid,medlinecitation.article.journal.issn.issn`. Fields have to be fully qualified by path. If you have multiple fields, they need to be comma-delimited.

![https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/SearchPubMed-QualifyByField.gif](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/SearchPubMed-QualifyByField.gif)

### Get count of results using "/pubmed/query-number-pubmed-articles/"

Given the number of results that can be returned for some searches, e.g., `Wang Y[au]`, it's helpful to know the number of results a given query returns.

1. Go to the Swagger interface for PubMed Retrieval Tool.
2. Click on `/pubmed/query-number-pubmed-articles/`
3. Enter search. For example:
```
{
  "strategy-query": "Bales ME[au]"
}
```
4. Click execute.

![https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/CountArticlesPubMed.gif](https://github.com/wcmc-its/ReCiter-PubMed-Retrieval-Tool/blob/master/files/CountArticlesPubMed.gif)


## Search using "/pubmed/query-complex/"

This API is used by the ReCiter application proper. Everything you could search for here, you should be able to search against "/pubmed/query/." Nonetheless, here are some examples for how you can use this query.

```
{
  "strategy-query": "Cole CL[au]"
}
```
```
{
  "strategy-query": "10.1007/s12026-013-8413-z[doi]"
}
```
```
{
  "doi": "10.1007/s12026-013-8413-z"
}
```
```
{
  "author": "Cole Curtis"
}
```
```
{
  "author": "Cole Curtis",
  "start": "2010/01/01"
}
```
```
{
  "author": "Cole Curtis",
  "start": "2010/01/01",
  "end": "2020/01/01"
}
```
