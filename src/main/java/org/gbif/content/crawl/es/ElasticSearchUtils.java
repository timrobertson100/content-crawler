package org.gbif.content.crawl.es;

import org.gbif.content.crawl.conf.ContentCrawlConfiguration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import com.google.common.base.CaseFormat;
import org.apache.commons.io.IOUtils;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

/**
 * Common ElasticSearch utility methods.
 */
public class ElasticSearchUtils {

  private static final Pattern REPLACEMENTS = Pattern.compile(":\\s+|\\s+");

  //This an alias used for all active cms/content indices
  private static final String CONTENT_ALIAS = "content";

  /**
   * Utility class must have private methods.
   */
  private ElasticSearchUtils() {
    //NOP
  }

  /**
   * Creates a new instance of a ElasticSearch client.
   */
  public static Client buildEsClient(ContentCrawlConfiguration.ElasticSearch configuration) {
    try {
      Settings settings = Settings.builder().put("cluster.name", configuration.cluster).build();
      return new PreBuiltTransportClient(settings).addTransportAddress(
        new InetSocketTransportAddress(InetAddress.getByName(configuration.host),
                                       configuration.port));
    } catch (UnknownHostException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Creates, if doesn't exists, an ElasticSearch index that matches the name of the contentType.
   * If the flag configuration.contentful.deleteIndex is ON and the index exist, it will be removed.
   */
  public static void createIndex(Client esClient, String typeName,
                                 String idxName, String source) {
    //create ES idx if it doesn't exists
    if (esClient.admin().indices().prepareExists(idxName).get().isExists()) {
      esClient.admin().indices().prepareDelete(idxName).get();
    }
    esClient.admin().indices().prepareCreate(idxName).addMapping(typeName, source).get();
  }

  /**
   * Creates, if doesn't exists, an ElasticSearch index that matches the name of the contentType.
   * If the flag configuration.contentful.deleteIndex is ON and the index exist, it will be removed.
   */
  public static void createIndex(Client esClient, ContentCrawlConfiguration.IndexBuild configuration,
                                 String source) {
    createIndex(esClient, configuration.esIndexType, getEsIndexingIdxName(configuration.esIndexName), source);
  }

  /**
   * This method delete all the indecxs associated to the alias and associates the alias to toIdx.
   */
  public static void swapIndexToAlias(Client esClient, String alias, String toIdx) {
    try {
      //Sets the idx alias
      esClient.admin().indices().prepareAliases().addAlias(toIdx, alias).get();
      //Promote new index to alla content indices
      esClient.admin().indices().prepareAliases().addAlias(toIdx, CONTENT_ALIAS).get();
      GetAliasesResponse aliasesResponse = esClient.admin().indices()
        .getAliases(new GetAliasesRequest().aliases(alias)).get();
      aliasesResponse.getAliases().keysIt().forEachRemaining(aliasedIdx -> {
        if (!toIdx.equals(aliasedIdx)) {
          esClient.admin().indices().prepareDelete(aliasedIdx).get();
        }
      });
    } catch (InterruptedException|ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Reads the content of ES_MAPPINGS_FILE into a String.
   */
  public static String indexMappings(String mappingsFileName) {
    try {
      return new String(IOUtils.toByteArray(Thread.currentThread().getContextClassLoader()
                                              .getResourceAsStream(mappingsFileName)));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Gets the ElasticSearch index name.
   */
  public static String getEsIdxName(String contentTypeName) {
    return REPLACEMENTS.matcher(contentTypeName).replaceAll("").toLowerCase();
  }

  /**
   * Index name to be used while indexing.
   * @return getEsIdxName(contentTypeName) + time in milliseconds
   */
  public static String getEsIndexingIdxName(String contentTypeName) {
    return getEsIdxName(contentTypeName) + new Date().getTime();
  }

  /**
   * Translates a sentence type text into upper camel case format.
   * For example: "Hola Morten" will be transformed into "holaMorten".
   */
  public static String toFieldNameFormat(CharSequence sentence) {
    return CaseFormat.UPPER_UNDERSCORE
      .to(CaseFormat.LOWER_CAMEL, REPLACEMENTS.matcher(sentence).replaceAll("_").toUpperCase());
  }

}
