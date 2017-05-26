package org.gbif.content.crawl.mendeley;

import org.gbif.api.model.occurrence.Download;
import org.gbif.api.service.occurrence.DownloadRequestService;
import org.gbif.api.service.registry.DatasetOccurrenceDownloadUsageService;
import org.gbif.api.service.registry.DatasetService;
import org.gbif.api.service.registry.OccurrenceDownloadService;
import org.gbif.api.util.VocabularyUtils;
import org.gbif.api.vocabulary.Country;
import org.gbif.api.vocabulary.Language;
import org.gbif.content.crawl.conf.ContentCrawlConfiguration;
import org.gbif.registry.ws.client.guice.RegistryWsClientModule;
import org.gbif.ws.client.guice.AnonymousAuthModule;
import org.gbif.ws.client.guice.GbifApplicationAuthModule;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.content.crawl.es.ElasticSearchUtils.buildEsClient;
import static org.gbif.content.crawl.es.ElasticSearchUtils.createIndex;
import static org.gbif.content.crawl.es.ElasticSearchUtils.indexMappings;
import static org.gbif.content.crawl.es.ElasticSearchUtils.getEsIdxName;
import static org.gbif.content.crawl.es.ElasticSearchUtils.getEsIndexingIdxName;
import static org.gbif.content.crawl.es.ElasticSearchUtils.swapIndexToAlias;

/**
 * Parses the documents from the response and adds them to the index.
 */
public class ElasticSearchIndexHandler implements ResponseHandler {

  //Mendeley fields used by this handler
  private static final String ML_ID_FL = "id";
  private static final String ML_TAGS_FL = "tags";
  private static final String ML_MONTH_FL = "month";
  private static final String ML_DAY_FL = "day";
  private static final String ML_YEAR_FL = "year";
  //Format used to store the createdAt field
  private static final String CREATED_AT_FMT = "yyyy-MM-dd'T00:00:00.000Z'";


  //Elasticsearch fields created by this handler
  private static final String ES_COUNTRY_RESEARCHER_FL = "countriesOfResearcher";
  private static final String ES_COUNTRY_COVERAGE_FL = "countriesOfCoverage";

  private static final String ES_CREATED_AT_FL = "createdAt";
  private static final String ES_UPDATED_AT_FL = "updatedAt";

  private static final String ES_GBIF_REGION_FL = "gbifRegion";
  private static final String ES_GBIF_DOI_FL = "gbifDOI";

  private static final String ES_MAPPING_FILE = "mendeley_mapping.json";

  private static final String LITERATURE_TYPE_FIELD = "literatureType";

  private static final String TYPE_FIELD = "type";

  private static final String CONTENT_TYPE_FIELD = "contentType";

  private static final String CONTENT_TYPE_FIELD_VALUE = "literature";

  private static final String LANGUAGE_FIELD = "language";

  private static final String GBIF_DOI_TAG = ES_GBIF_DOI_FL + ':';

  private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchIndexHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public static final String LAST_MODIFIED = "last_modified";

  private final Client esClient;
  private final ContentCrawlConfiguration conf;
  private final String esIdxName;
  private final OccurrenceDownloadService downloadService;
  private final DatasetService datasetService;

  public ElasticSearchIndexHandler(ContentCrawlConfiguration conf) {
    this.conf = conf;
    LOG.info("Connecting to ES cluster {}:{}", conf.elasticSearch.host, conf.elasticSearch.port);
    esClient = buildEsClient(conf.elasticSearch);
    esIdxName = getEsIndexingIdxName(conf.mendeley.indexBuild.esIndexName);
    Properties registryConf = new Properties();
    registryConf.put("registry.ws.url",conf.mendeley.gbifApiUrl);
    Injector injector = Guice.createInjector(new RegistryWsClientModule(registryConf), new AnonymousAuthModule());
    downloadService = injector.getInstance(OccurrenceDownloadService.class);
    datasetService = injector.getInstance(DatasetService.class);
    createIndex(esClient, conf.mendeley.indexBuild.esIndexType, esIdxName, indexMappings(ES_MAPPING_FILE));
  }

  /**
   * Bulk loads the response as JSON into ES.
   * @param responseAsJson To load.
   */
  @Override
  public void handleResponse(String responseAsJson) throws IOException {
    BulkRequestBuilder bulkRequest = esClient.prepareBulk();
    //process each Json node
    MAPPER.readTree(responseAsJson).elements().forEachRemaining(document -> {
      toCamelCasedFields(document);
      manageReplacements((ObjectNode)document);
      if (document.has(ML_TAGS_FL)) {
        handleTags(document);
      }
      bulkRequest.add(esClient.prepareIndex(esIdxName, conf.mendeley.indexBuild.esIndexType,
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
  private void handleTags(JsonNode document) {
    Set<TextNode> countriesOfResearches = new HashSet<>();
    Set<TextNode> countriesOfCoverage = new HashSet<>();
    Set<TextNode> regions = new HashSet<>();
    Set<TextNode> gbifDatasets = new HashSet<>();
    document.get(ML_TAGS_FL).elements().forEachRemaining(node -> {
      String value = node.textValue();
      if (value.startsWith(GBIF_DOI_TAG)) {
        String keyValue  = value.replaceFirst(GBIF_DOI_TAG,"");
        Optional<Download> downloadOpt = Optional.ofNullable(downloadService.get(keyValue));
        downloadOpt.ifPresent(download ->
          new DatasetUsageIterable(downloadService, download.getKey())
            .forEach(response -> response.getResults()
              .forEach(usage -> gbifDatasets.add(TextNode.valueOf(usage.getDatasetKey().toString()))))
        );
        if(!downloadOpt.isPresent()) {
          Optional.ofNullable(datasetService.listByDOI(keyValue))
            .ifPresent(datasets ->
                         datasets.forEach(dataset -> gbifDatasets.add(TextNode.valueOf(dataset.getKey().toString()))));
        }
      } else { //try country parser
        Optional.ofNullable(Country.fromIsoCode(value)).ifPresent(country -> countriesOfResearches.add(TextNode.valueOf(country.getIso2LetterCode())));

        //VocabularyUtils uses Guava optionals
        com.google.common.base.Optional<Country> bioCountry = VocabularyUtils.lookup(value, Country.class);
        if (bioCountry.isPresent()) {
          Country bioCountryValue = bioCountry.get();
          countriesOfCoverage.add(TextNode.valueOf(bioCountryValue.getIso2LetterCode()));
          Optional.ofNullable(bioCountryValue.getGbifRegion()).ifPresent(region -> regions.add(TextNode.valueOf(region.name())));
        }
      }
    });
    ObjectNode docNode  = (ObjectNode)document;
    docNode.putArray(ES_COUNTRY_RESEARCHER_FL).addAll(countriesOfResearches);
    docNode.putArray(ES_COUNTRY_COVERAGE_FL).addAll(countriesOfCoverage);
    docNode.putArray(ES_GBIF_REGION_FL).addAll(regions);
    docNode.putArray(ES_GBIF_DOI_FL).addAll(gbifDatasets);
    docNode.put(CONTENT_TYPE_FIELD, CONTENT_TYPE_FIELD_VALUE);
  }

  /**
   * Evaluates the fields year, month and day to calculate the createdAt field.
   * Some documents in Mendeley are reported with incorrect 'day of the month' values, this function uses
   * Date math to avoid errors at indexing time in ElasticSearch.
   */
  private static Optional<String> createdAt(ObjectNode objectNode) {
    return Optional.ofNullable(objectNode.get(ML_YEAR_FL))
            .map(JsonNode::asText)
            .map(yearValue -> LocalDate.ofYearDay(Integer.parseInt(yearValue),1)
                              .withMonth(getDateBasedField(objectNode, ML_MONTH_FL))
                              .plusDays(getDateBasedField(objectNode, ML_DAY_FL) - 1)
                              .atStartOfDay()
                              .format(DateTimeFormatter.ofPattern(CREATED_AT_FMT)));
  }

  /**
   * Gets the integer value of fields month and day.
   */
  private static Integer getDateBasedField(ObjectNode objectNode, String field) {
    return Optional.ofNullable(objectNode.get(field))
            .map(monthNode -> Integer.parseInt(monthNode.asText()))
            .orElse(1);
  }

  /**
   * Replaces the specials field names and values.
   *  - Replaces field name type for literature_type.
   *  - Replaces the language name for ISOLanguage code.
   */
  private static void manageReplacements(ObjectNode docNode) {
    Optional.ofNullable(docNode.get(TYPE_FIELD)).ifPresent(typeNode -> {
      docNode.set(LITERATURE_TYPE_FIELD, typeNode);
      docNode.remove(TYPE_FIELD);
    });
    Optional.ofNullable(docNode.get(LANGUAGE_FIELD)).ifPresent(typeNode -> {
      String languageValue = typeNode.asText();
      Optional<Language> language = Arrays.stream(Language.values())
        .filter(gbifLanguage -> gbifLanguage.getTitleEnglish().equalsIgnoreCase(languageValue)
                                   || gbifLanguage.getTitleNative().equalsIgnoreCase(languageValue)
                                   || gbifLanguage.getIso2LetterCode().equalsIgnoreCase(languageValue)
                                   || gbifLanguage.getIso3LetterCode().equalsIgnoreCase(languageValue))
        .findFirst();
      if (language.isPresent()) {
        docNode.set(LANGUAGE_FIELD, TextNode.valueOf(language.get().getIso3LetterCode()));
      } else {
        LOG.warn("Removing unknown language {} from document {}", languageValue, docNode.get(ML_ID_FL));
        docNode.set(LANGUAGE_FIELD, TextNode.valueOf(Language.UNKNOWN.getIso3LetterCode()));
      }

    });
    createdAt(docNode)
      .ifPresent(createdAtValue -> docNode.put(ES_CREATED_AT_FL, createdAtValue));
  }

  /**
   * Transforms all the fields' names from lower_underscore to lowerCame.
   */
  private static void toCamelCasedFields(JsonNode root) {
    Map<String,JsonNode> nodes = Maps.toMap(root.fieldNames(), root::get);
    nodes.forEach((fieldName, nodeValue) -> {
      normalizeName(root, nodeValue, fieldName);
      if (nodeValue.isObject()) {
        toCamelCasedFields(nodeValue);
      } else if (nodeValue.isArray()) {
        nodeValue.elements().forEachRemaining(ElasticSearchIndexHandler::toCamelCasedFields);
      }
    });
  }

  /**
   * Changes the name to the lowerCamelCase and replace some field names.
   */
  private static void normalizeName(JsonNode root, JsonNode nodeValue, String fieldName) {
    if (fieldName.equals(LAST_MODIFIED)) {
      replaceNodeName((ObjectNode) root, nodeValue, fieldName, ES_UPDATED_AT_FL);
    } else {
      String camelCaseName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, fieldName);
      if (!camelCaseName.equals(fieldName)) {
        replaceNodeName((ObjectNode) root, nodeValue, fieldName, camelCaseName);
      }
    }
  }

  /**
   * Replaces an node name.
   */
  private static void replaceNodeName(ObjectNode root, JsonNode nodeValue, String fieldName, String newName) {
    root.set(newName, nodeValue);
    root.remove(fieldName);
  }

  @Override
  public void finish() {
    swapIndexToAlias(esClient, getEsIdxName(conf.mendeley.indexBuild.esIndexName), esIdxName);
    esClient.close();
  }
}
