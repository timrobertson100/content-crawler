package org.gbif.content.crawl.contentful.crawl;

import static org.gbif.content.crawl.es.ElasticSearchUtils.getEsIdxName;

import java.util.Collection;
import java.util.Collections;

import com.contentful.java.cda.CDAEntry;
import org.elasticsearch.client.Client;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to create links between content elements in the ES Document.
 */
public class ESDocumentLinker {

  private static final Logger LOG = LoggerFactory.getLogger(ESDocumentLinker.class);

  private static final String NEWS_UPDATE_SCRIPT  = "if (ctx._source.%1$s == null) "  //field doesn't exist
                                                    + "{ctx._source.%1$s = [params.tag]} " //create new list/array
                                                    + "else if (ctx._source.%1$s.contains(params.tag)) " //value exists
                                                    + "{ ctx.op = \"none\"  } " //do nothing
                                                    + "else { ctx._source.%1$s.add(params.tag) }"; //add new value

  private final String targetContentTypeId;

  private final Client esClient;

  private final String esTargetIndexType;

  public ESDocumentLinker(String targetContentTypeId, Client esClient, String esTargetIndexType) {
    this.esClient = esClient;
    this.targetContentTypeId =  targetContentTypeId;
    this.esTargetIndexType = esTargetIndexType;
  }

  /**
   * This method updates the news index by adding a new field [contentTypeName]Tag which stores all the ids
   * related to that item from this content type, it is used for creating RSS feeds for specific elements.
   */
  private void processEntryTag(CDAEntry cdaEntry, String esTypeName, String tagValue) {
    if (cdaEntry.contentType().id().equals(targetContentTypeId)) {
      insertTag(cdaEntry, esTypeName, tagValue);
    }
  }

  /**
   * Processes the item associated to an entry.
   * Accepts list of localized resources and a single CDAEntry.
   */
  public void processEntryTag(Object entry, String esTypeName, String tagValue) {
    if (Collection.class.isInstance(entry)) {
      processEntryTag((Collection<?>)entry, esTypeName, tagValue);
    } else {
      processEntryTag((CDAEntry)entry, esTypeName, tagValue);
    }
  }

  /**
   * Processes a list of possible entries.
   */
  private void processEntryTag(Collection<?> resources, String esTypeName, String tagValue) {
    resources.stream()
      .filter(resource -> CDAEntry.class.isInstance(resource)
                          && ((CDAEntry) resource).contentType().id().equals(targetContentTypeId))
      .forEach(cdaEntry -> insertTag((CDAEntry) cdaEntry, esTypeName, tagValue));
  }

  /**
   * Inserts the tag in the News index.
   */
  private void insertTag(CDAEntry cdaEntry, String esTypeName, String tagValue) {
    try {
      Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG,
                                 String.format(NEWS_UPDATE_SCRIPT, esTypeName + "Tag"),
                                 Collections.singletonMap("tag", tagValue));
      esClient.prepareUpdate(getEsIdxName(cdaEntry.contentType().name()),
                          esTargetIndexType, cdaEntry.id()).setScript(script).get();
    } catch (Exception ex) {
      LOG.error("Error updating news tag {} from entry {} ", tagValue, cdaEntry, ex);
    }
  }
}
