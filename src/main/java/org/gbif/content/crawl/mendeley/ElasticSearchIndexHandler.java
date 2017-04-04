package org.gbif.content.crawl.mendeley;

import org.gbif.api.util.VocabularyUtils;
import org.gbif.api.vocabulary.Country;
import org.gbif.content.crawl.conf.ContentCrawlConfiguration;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.content.crawl.es.ElasticSearchUtils.buildEsClient;
import static org.gbif.content.crawl.es.ElasticSearchUtils.createIndex;

/**
 * Parses the documents from the response and adds them to the index.
 */
public class ElasticSearchIndexHandler implements ResponseHandler {

  //Mendeley fields used by this handler
  private static final String ML_ID_FL = "id";
  private static final String ML_TAGS_FL = "tags";

  //Elasticsearch fields created by this handler
  private static final String ES_AUTHORS_COUNTRY_FL = "authorsCountry";
  private static final String ES_BIODIVERSITY_COUNTRY_FL = "biodiversityCountry";
  private static final String ES_GBIF_REGION_FL = "gbifRegion";

  private static final String ES_MAPPING_FILE = "mendeley_mapping.json";

  private static final String LITERATURE_TYPE_FIELD = "literatureType";

  private static final String TYPE_FIELD = "type";

  private static final String CONTENT_TYPE_FIELD = "contentType";

  private static final String CONTENT_TYPE_FIELD_VALUE = "literature";

  private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchIndexHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Client client;
  private final ContentCrawlConfiguration conf;

  public ElasticSearchIndexHandler(ContentCrawlConfiguration conf) {
    this.conf = conf;
    LOG.info("Connecting to ES cluster {}:{}", conf.elasticSearch.host, conf.elasticSearch.port);
    client = buildEsClient(conf.elasticSearch);
    createIndex(client, conf.mendeley.indexBuild, ES_MAPPING_FILE);
  }

  /**
   * Bulk loads the response as JSON into ES.
   * @param responseAsJson To load.
   */
  @Override
  public void handleResponse(String responseAsJson) throws IOException {
    BulkRequestBuilder bulkRequest = client.prepareBulk();
    //process each Json node
    MAPPER.readTree(responseAsJson).elements().forEachRemaining(document -> {
      toCamelCasedFields(document);
      replaceTypeField((ObjectNode)document);
      if (document.has(ML_TAGS_FL)) {
        handleTags(document);
      }
      bulkRequest.add(client.prepareIndex(conf.mendeley.indexBuild.esIndexName, conf.mendeley.indexBuild.esIndexType,
                                          document.get(ML_ID_FL).asText()).setSource(document.toString()));
    });

    BulkResponse bulkResponse = bulkRequest.get();
    if (bulkResponse.hasFailures()) {
      LOG.error("Error indexing.  First error message: {}", bulkResponse.getItems()[0].getFailureMessage());
    } else {
      LOG.info("Indexed [{}] documents", bulkResponse.getItems().length);
    }
  }

  /**
   * Process tags. Adds publishers countries and biodiversity countries from tag values.
   */
  private static void handleTags(JsonNode document) {
    Set<TextNode> publishersCountries = new HashSet<>();
    Set<TextNode> biodiversityCountries = new HashSet<>();
    Set<TextNode> regions = new HashSet<>();
    document.get(ML_TAGS_FL).elements().forEachRemaining(node -> {
      String value = node.textValue();
      Optional.ofNullable(Country.fromIsoCode(value))
        .ifPresent(country -> publishersCountries.add(TextNode.valueOf(country.getIso2LetterCode())));

      //VocabularyUtils uses Guava optionals
      com.google.common.base.Optional<Country> bioCountry = VocabularyUtils.lookup(value, Country.class);
      if (bioCountry.isPresent()) {
        Country bioCountryValue = bioCountry.get();
        biodiversityCountries.add(TextNode.valueOf(bioCountryValue.getIso2LetterCode()));
        Optional.ofNullable(bioCountryValue.getGbifRegion())
          .ifPresent(region -> regions.add(TextNode.valueOf(region.name())));
      }
    });
    ObjectNode docNode  = (ObjectNode)document;
    docNode.putArray(ES_AUTHORS_COUNTRY_FL).addAll(publishersCountries);
    docNode.putArray(ES_BIODIVERSITY_COUNTRY_FL).addAll(biodiversityCountries);
    docNode.putArray(ES_GBIF_REGION_FL).addAll(regions);
    docNode.put(CONTENT_TYPE_FIELD, CONTENT_TYPE_FIELD_VALUE);
  }

  /**
   * Replace the type field name for literature_type.
   */
  private static void replaceTypeField(ObjectNode docNode) {
    Optional.ofNullable(docNode.get(TYPE_FIELD)).ifPresent(typeNode -> {
      docNode.set(LITERATURE_TYPE_FIELD, typeNode);
      docNode.remove(TYPE_FIELD);
    });
  }

  /**
   * Transforms all the fields' names from lower_underscore to lowerCame.
   */
  private static void toCamelCasedFields(JsonNode root) {
    Map<String,JsonNode> nodes = Maps.toMap(root.fieldNames(), root::get);
    nodes.forEach((fieldName, nodeValue) -> {
      replaceIfLowerUnderScore(root, nodeValue, fieldName);
      if (nodeValue.isObject()) {
        toCamelCasedFields(nodeValue);
      } else if (nodeValue.isArray()) {
        nodeValue.elements().forEachRemaining(ElasticSearchIndexHandler::toCamelCasedFields);
      }
    });
  }

  private static  void replaceIfLowerUnderScore(JsonNode root, JsonNode nodeValue, String fieldName){
    String camelCaseName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, fieldName);
    if (!camelCaseName.equals(fieldName)) {
      ((ObjectNode) root).set(camelCaseName, nodeValue);
      ((ObjectNode) root).remove(fieldName);
    }
  }



  @Override
  public void finish() {
    client.close();
  }
}
