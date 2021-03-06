package org.gbif.content.crawl.contentful.crawl;

import org.elasticsearch.common.unit.TimeValue;
import org.gbif.content.crawl.conf.ContentCrawlConfiguration;

import java.util.HashMap;
import java.util.Map;

import com.contentful.java.cda.CDAClient;
import com.contentful.java.cda.CDAEntry;
import com.contentful.java.cma.model.CMAContentType;
import io.reactivex.Observable;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.content.crawl.es.ElasticSearchUtils.getEsIdxName;
import static org.gbif.content.crawl.es.ElasticSearchUtils.getEsIndexingIdxName;
import static org.gbif.content.crawl.es.ElasticSearchUtils.createIndex;
import static org.gbif.content.crawl.es.ElasticSearchUtils.swapIndexToAlias;
import static org.gbif.content.crawl.es.ElasticSearchUtils.toFieldNameFormat;

/**
 * Crawls a single Contentful content type.
 */
public class ContentTypeCrawler {

  //Buffer to use in Observables to accumulate results before send them to ElasticSearch
  private static final int CRAWL_BUFFER = 10;

  private static final Logger LOG = LoggerFactory.getLogger(ContentTypeCrawler.class);

  private static final String CONTENT_TYPE_FIELD = "contentType";

  private static final int PAGE_SIZE = 20;

  private static final TimeValue BULK_REQUEST_TO = TimeValue.timeValueMinutes(5);

  private final CMAContentType contentType;

  private final String esIdxName;

  private final String esIdxAlias;

  private final String esTypeName;

  //Linkers decorate existing entries in the index with supplementary information (like tags)
  private final ESDocumentLinker newsLinker;
  private final ESDocumentLinker articleLinker;

  private final MappingGenerator mappingGenerator;
  private final Client esClient;
  private final CDAClient cdaClient;
  private final ContentCrawlConfiguration.Contentful configuration;
  private final VocabularyTerms vocabularyTerms;


  ContentTypeCrawler(CMAContentType contentType,
                     MappingGenerator mappingGenerator,
                     Client esClient,
                     ContentCrawlConfiguration.Contentful configuration,
                     CDAClient cdaClient,
                     VocabularyTerms vocabularyTerms,
                     String newsContentTypeId,
                     String articleContentTypeId) {
    this.contentType = contentType;
    //index name has to be in lowercase
    esIdxName = getEsIndexingIdxName(contentType.getName());
    //Index alias
    esIdxAlias = getEsIdxName(contentType.getName());
    //ES type name for this content typ
    esTypeName = toFieldNameFormat(contentType.getName());
    //Used to create links in the indexes
    newsLinker = new ESDocumentLinker(newsContentTypeId, esClient, configuration.indexBuild.esIndexType);
    articleLinker = new ESDocumentLinker(articleContentTypeId, esClient, configuration.indexBuild.esIndexType);

    //Set the mapping generator
    this.mappingGenerator = mappingGenerator;

    this.esClient = esClient;

    this.configuration = configuration;

    this.cdaClient = cdaClient;

    this.vocabularyTerms = vocabularyTerms;
  }

  /**
   * Crawls the assigned content type into ElasticSearch.
   */
  public void crawl() {
    //gets or (re)create the ES idx if doesn't exists
    createIndex(esClient, configuration.indexBuild.esIndexType, esIdxName, mappingGenerator.getEsMapping(contentType));
    LOG.info("Indexing ContentType [{}] into ES Index [{}]", contentType.getName(), esIdxName);
    //Prepares the bulk/batch request
    BulkRequestBuilder bulkRequest = esClient.prepareBulk();
    //Retrieves resources in a CDAArray
    Observable.fromIterable(new ContentfulPager(cdaClient, PAGE_SIZE, contentType.getResourceId()))
      .doOnError(err -> { LOG.error("Error crawling content type", err);
                          throw new RuntimeException(err);
                        })
      .buffer(CRAWL_BUFFER)
      .doOnComplete(() -> {
         if(executeBulkRequest(bulkRequest)) {
           swapIndexToAlias(esClient, esIdxAlias, esIdxName);
         }
      })
      .subscribe( results -> results.forEach(
                              cdaArray -> cdaArray.items()
                              .forEach(cdaResource ->
                                         bulkRequest.add(esClient.prepareIndex(esIdxName,
                                                                               configuration.indexBuild.esIndexType,
                                                                               cdaResource.id())
                                                           .setSource(getESDoc((CDAEntry)cdaResource)))))
      );
  }

  /**
   * Extracts the fields that will be indexed in ElasticSearch.
   */
  private Map<String,Object> getESDoc(CDAEntry cdaEntry) {
    EsDocBuilder esDocBuilder = new EsDocBuilder(cdaEntry, vocabularyTerms,
            nestedCdaEntry -> {
              // decorate any entries, linkers are responsible for filtering suitable content types
              newsLinker.processEntryTag(nestedCdaEntry, esTypeName, cdaEntry.id());
              articleLinker.processEntryTag(nestedCdaEntry, esTypeName, cdaEntry.id());
            });
    //Add all rawFields
    Map<String, Object> indexedFields =  new HashMap<>(esDocBuilder.toEsDoc());
    indexedFields.put(CONTENT_TYPE_FIELD, esTypeName);
    return indexedFields;
  }


  /**
   * Performs the execution of a ElasticSearch BulkRequest and logs the correspondent results.
   */
  private boolean executeBulkRequest(BulkRequestBuilder bulkRequest) {
    if (bulkRequest.numberOfActions() > 0) {
      bulkRequest.setTimeout(BULK_REQUEST_TO);
      LOG.info("Indexing {} documents into ElasticSearch", bulkRequest.numberOfActions());
      BulkResponse bulkResponse = bulkRequest.get();
      if (bulkResponse.hasFailures()) {
        LOG.error("Error indexing.  First error message: {}", bulkResponse.getItems()[0].getFailureMessage());
        return false;
      } else {
        LOG.info("Indexed [{}] documents of content type [{}]", bulkResponse.getItems().length, esIdxName);
      }
    } else  {
      LOG.info("Nothing to index for content type [{}]", esIdxName);
    }
    return true;
  }

}
