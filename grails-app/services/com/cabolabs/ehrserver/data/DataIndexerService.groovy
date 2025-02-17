/*
 * Copyright 2011-2020 CaboLabs Health Informatics
 *
 * The EHRServer was designed and developed by Pablo Pazos Gutierrez <pablo.pazos@cabolabs.com> at CaboLabs Health Informatics (www.cabolabs.com).
 *
 * You can't remove this notice from the source code, you can't remove the "Powered by CaboLabs" from the UI, you can't remove this notice from the window that appears then the "Powered by CaboLabs" link is clicked.
 *
 * Any modifications to the provided source code can be stated below this notice.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cabolabs.ehrserver.data

import com.cabolabs.ehrserver.exceptions.DataIndexException
import com.cabolabs.ehrserver.exceptions.VersionRepoNotAccessibleException
import java.io.FileNotFoundException
import com.cabolabs.ehrserver.ehr.clinical_documents.data.*
import com.cabolabs.ehrserver.ehr.clinical_documents.CompositionIndex
import com.cabolabs.ehrserver.ehr.clinical_documents.ArchetypeIndexItem
import com.cabolabs.ehrserver.ehr.clinical_documents.OperationalTemplateIndex
import grails.transaction.Transactional
import groovy.util.slurpersupport.GPathResult
import grails.util.Holders
import org.xml.sax.ErrorHandler
import com.cabolabs.util.DateParser
import com.cabolabs.ehrserver.data.DataValues
import com.cabolabs.ehrserver.notification.Notification
import com.cabolabs.ehrserver.indexing.DataValueIndexLog
import com.cabolabs.security.*

@Transactional
class DataIndexerService {

   def config = Holders.config.app
   def versionRepoService

   def generateIndexes(CompositionIndex compoIndex)
   {
      // created indexes will be loaded here
      def indexes = []
      def version, versionXml, parsedVersion, compoParsed

      // Error handler to avoid:
      // Warning: validation was turned on but an org.xml.sax.ErrorHandler was not
      // set, which is probably not what is desired.  Parser will use a default
      // ErrorHandler to print the first 10 errors.  Please call
      // the 'setErrorHandler' method to fix this.
      def message
      def parser = new XmlSlurper(false, false)
      // parser.setErrorHandler( { message = it.message } as ErrorHandler ) // https://github.com/groovy/groovy-core/blob/master/subprojects/groovy-xml/src/test/groovy/groovy/xml/XmlUtilTest.groovy

      if (OperationalTemplateIndex.countByTemplateId(compoIndex.templateId) == 0)
      {
         // TODO: send a notification to the org managers and add a dsimissable notification for them (TBD)
         log.warn "The committed composition ${compoIndex.uid} references a template '${compoIndex.templateId}' that is not loaded. Indexing is avoided until the template is loaded."
         return
      }

      indexes = []

      // load xml file from filesystem
      version = compoIndex.getParent()

      try
      {
         versionXml = versionRepoService.getExistingVersionContents(compoIndex.organizationUid, version)
      }
      catch (VersionRepoNotAccessibleException e)
      {
         log.warn e.message
         return // continue with next compoIndex
      }
      catch (FileNotFoundException e)
      {
         log.error "Committed file not found, avoiding indexing version "+ version.uid +" "+ e.message
         return // Continue with next compoIdx
      }

      parsedVersion = parser.parseText(versionXml)

      //       error from error handler?
      //       if (message)
      //       {
      //         println "IndexDataJob XML ERROR: "+ message
      //         message = null // empty for the next parse
      //       }

      compoParsed = parsedVersion.data

      process_COMPOSITION_index(compoParsed, compoIndex.templateId, '', compoIndex.archetypeId, '', compoIndex, indexes)

      log.debug "index count: "+ indexes.size()

      // empty if the OPT for the compo is not loaded in the server

      def aidx
      indexes.each { didx ->

         //println didx.archetypePath

         if (!didx.save())
         {
            log.error "index error: ("+ didx.templateId +") "+didx.archetypeId + didx.archetypePath +" "+ didx.rmTypeName +" "+ didx.getClass().getSimpleName() +' for compo '+ didx.owner.uid
            log.error didx.errors.toString()
            // if one index created fails to save, the whole indexing process is rolled back

            throw new DataIndexException('Index failed to save', didx.errors, didx.toString())
         }
         else
         {
            log.debug "index created: "+ didx.archetypeId + didx.archetypePath +' for compo '+ didx.owner.uid
         }


         // check if the AII exists, if not, the indexed value wont be able to be queried
         // there is a known issue with paths to ism_transitions, this is to check if besides that
         // there is another path that do not match between OPT indexes and DV indexes
         aidx = ArchetypeIndexItem.findByArchetypeIdAndPath(didx.archetypeId, didx.archetypePath)
         if (!aidx)
         {
            log.warn 'There is no ArchetypeIndexItem for the DataValueIndex '+ didx.archetypeId +" "+ didx.archetypePath +' TID: '+ compoIndex.templateId +' COMPOSER: '+ compoIndex.composer?.name

            new DataValueIndexLog(index: didx, message: 'There is no ArchetypeIndexItem for the DataValueIndex '+didx.archetypeId +" "+ didx.archetypePath +' TID: '+ compoIndex.templateId +' COMPOSER: '+ compoIndex.composer?.name).save()

            def admins = UserRole.findAllByRole(Role.findByAuthority(Role.AD)).user
            admins.each{ admin ->
               new Notification(
                  name:     'There is no ArchetypeIndexItem for the DataValueIndex',
                  language: 'en', // TODO: lang should be the preferred by the org 0 of the admin
                  text:     'There is no ArchetypeIndexItem for the DataValueIndex '+didx.archetypeId +" "+ didx.archetypePath,
                  forUser:  admin.id).save()
            }
         }
      }

      // all indexes created were saved correctly!
      compoIndex.dataIndexed = true

      if (!compoIndex.save())
      {
         log.error "Error al guardar compoIndex: "+ compoIndex.errors.toString()
         throw new DataIndexException('CompiIndex failed to save omn indexing', compoIndex.errors)
      }
      else
      {
         log.info "Composition ${compoIndex.uid} indexed"
      }
   }

   private Map getChildPathsAndRootArchetype(node, templatePath, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass = null)
   {
      // Path del template para el indice (absoluta)
      String outTemplatePath
      String outArchetypePath
      String instanceTemplatePath

      // La path del root va sin nombre, ej. sin esto el root que es / seria /data
      if (templatePath == '')
      {
        outTemplatePath = '/'
        outArchetypePath = '/'
        instanceTemplatePath = '/'
      }
      else if (!node.'@archetype_node_id'.isEmpty()) // Si tiene archetype_node_id
      {
        // Para que los hijos de la raiz no empiecen con //
        if (templatePath == '/') templatePath = ''
        if (archetypePath == '/') archetypePath = ''
        if (parentInstancePath == '/') parentInstancePath = ''

        // Si es un nodo atNNNN
        if (node.'@archetype_node_id'.text().startsWith('at'))
        {
          outTemplatePath = templatePath + '/' + node.name() + '[' + node.'@archetype_node_id'.text() + ']'
          outArchetypePath = archetypePath + '/' + node.name() + '[' + node.'@archetype_node_id'.text() + ']'
          instanceTemplatePath = parentInstancePath + '/' + node.name() + '[' + node.'@archetype_node_id'.text() + ']'
        }
        else // Si es un archetypeId
        {
          outTemplatePath = templatePath + '/' + node.name() + '[archetype_id='+ node.'@archetype_node_id'.text() +']'
          outArchetypePath = '/' // This node is an archetype root because it has an archetypeId
          archetypeId = node.'@archetype_node_id'.text()
          instanceTemplatePath = parentInstancePath + '/' + node.name() + '[archetype_id='+ node.'@archetype_node_id'.text() +']'
        }
      }
      else // No tiene archetype_node_id
      {
        // Para que los hijos de la raiz no empiecen con //
        if (templatePath == '/') templatePath = ''
        if (archetypePath == '/') archetypePath = ''
        if (parentInstancePath == '/') parentInstancePath = ''

        outTemplatePath = templatePath + '/' + node.name()
        outArchetypePath = archetypePath + '/' + node.name()
        instanceTemplatePath = parentInstancePath + '/' + node.name()
      }

      // instance paths
      def avoid = ['/', '/context']
      def collection_attrs = [
         'COMPOSITION': ['content'],
         'SECTION': ['items'],
         'HISTORY': ['events'],
         'INSTRUCTION': ['activities'],
         'ITEM_TREE': ['items'],
         'ITEM_LIST': ['items'],
         'ITEM_TABLE': ['rows'],
         'CLUSTER': ['items']
      ]

      def avoid_index = [ // these class.attr should not have index because are not collections (should be index_collection_attrs instead of avoid the rest)
         'ELEMENT': ['value']
      ]
      if (!avoid.contains(outTemplatePath))
      {
         //println "parentClass ${parentClass}, attr ${node.name()}"
         //if (avoid_index[parentClass] && avoid_index[parentClass].contains(node.name())) { avoid }
         if (collection_attrs[parentClass] && collection_attrs[parentClass].contains(node.name()))
         {
            instanceTemplatePath = instanceTemplatePath +'('+ multiple_index +')'
         }
      }

      return [templatePath: outTemplatePath, archetypePath: outArchetypePath, rootArchetype: archetypeId, instanceTemplatePath: instanceTemplatePath]
   }

   /**
    * @param path parent node path, absolute to the template, empty for the root node.
    * @param archetypePath absolute path to a root archetype but not to the template, used for querying.
    * @param node
    * @param indexes will contain all the indexes created by the recursion
    * @param templateId
    * @param archetypeId
    * @param owner
    */
    /*
     generic method to do all the recursive calls
    */
   private void keepProcessing(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes,
      Map attributes,
      int multiple_index, String parentInstancePath, String parentClass) // multiple_index is the multiple_children_index that comes from the parent to this node, and should be added to the current node's templatePath
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths "+ paths.instanceTemplatePath

      // this continues the recursion, the code is generic can be reused
      def child_type
      def multiple_children_index // for creating instance template path, to identify each occurrence of a node with the same path in a collection.
      attributes.each { attr, type ->

         //println "children in $attr "+ node[attr].size() +" "+ node[attr]*.'@xsi:type'

         // avoid processing metadata attribute _class as a common attribute
         if (attr == '_class') return

         // node[attr] can be multiple, and we need to process individual nodes in $method
         multiple_children_index = 0
         node[attr].each { child_attr_node ->

            if (type == '_ask_node_')
            {
               child_type = child_attr_node['@xsi:type']
            }
            else
            {
               child_type = type
            }

            def method = 'process_'+ child_type +'_index'
            //println "method: "+ method +" node: "+ child_attr_node.name() // +" "+ child_attr_node.getClass()
            this."$method"(child_attr_node, templateId, paths.templatePath, paths.rootArchetype, paths.archetypePath, owner, indexes, multiple_children_index, paths.instanceTemplatePath, attributes['_class'])

            multiple_children_index++
         }
      }
   }

   /*
   def methodMissing(String name, args)
   {
      println "-- Method Missing "+ name +" "+ args*.getClass() +" ----------------------------------------------"
   }
   */

   // TODO:
   // Refactor all these methods are just implementing a map of class => [attribute]
   // the real logic is on the generic method keepProcessing.
   //

   private void process_COMPOSITION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes)
   {
      // Attributes already indexed on CompoIndex are avoided
      def attributes = [
         '_class' : 'COMPOSITION',
         //'language': 'CODE_PHRASE',
         //'territory': 'CODE_PHRASE',
         'context': 'EVENT_CONTEXT',
         'content': '_ask_node_' // This is ENTRY but can be ACTION, OBSERVATION, etc.
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, 0, path, '')
   }

   private void process_EVENT_CONTEXT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      // start time is already indexed by compo index
      def attributes = [
         '_class' : 'EVENT_CONTEXT',
         'location': 'String',
         //'health_care_facility': 'PARTY_IDENTIFIED', // TODO, might be indexed with the compo
         'setting': 'DV_CODED_TEXT',
         'start_time': 'DV_DATE_TIME',
         'end_time': 'DV_DATE_TIME',
         'other_context': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_SECTION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class' : 'SECTION',
         'items': '_ask_node_' // CONTENT_ITEM
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ADMIN_ENTRY_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ADMIN_ENTRY',
         'data': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_EVALUATION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'EVALUATION',
         'data': '_ask_node_', // ITEM_STRUCTURE
         'protocol': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ACTION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ACTION',
         'time': 'DV_DATE_TIME',
         'description': '_ask_node_', // ITEM_STRUCTURE
         'ism_transition': 'ISM_TRANSITION',
         'instruction_details': 'INSTRUCTION_DETAILS',
         'protocol': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ISM_TRANSITION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ISM_TRANSITION',
         'current_state': 'DV_CODED_TEXT',
         'transition': 'DV_CODED_TEXT',
         'careflow_step': 'DV_CODED_TEXT'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_INSTRUCTION_DETAILS_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'LOCATABLE_REF',
         'instruction_id': 'LOCATABLE_REF',
         'activity_id': 'String',
         'wf_details': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_INSTRUCTION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'INSTRUCTION',
         'narrative': 'DV_TEXT',
         'expiry_time': 'DV_DATE_TIME',
         'protocol': '_ask_node_', // ITEM_STRUCTURE
         'activities': 'ACTIVITY'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ACTIVITY_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ACTIVITY',
         'description': '_ask_node_', // ITEM_STRUCTURE
         'timing': 'DV_PARSABLE',
         'action_archetype_id': 'String'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_OBSERVATION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'OBSERVATION',
         'data': 'HISTORY',
         'state': 'HISTORY',
         'protocol': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_HISTORY_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'HISTORY',
         'origin': 'DV_DATE_TIME',
         'period': 'DV_DURATION',
         'duration': 'DV_DURATION',
         'summary': '_ask_node_', // ITEM_STRUCTURE
         'events': '_ask_node_' // POINT_EVENT, INTERVAL_EVENT
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   // FIXME: if this is called, the committer is using the abstract type EVENT
   // instead of POINT_EVENT, should return a warning.
   private void process_EVENT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      // For now treate EVENT as POINT_EVENT
      process_POINT_EVENT_index(node, templateId, path, archetypeId, archetypePath, owner, indexes, multiple_index)
   }

   private void process_POINT_EVENT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'POINT_EVENT',
         'time': 'DV_DATE_TIME',
         'state': '_ask_node_', // ITEM_STRUCTURE
         'data': '_ask_node_' // ITEM_STRUCTURE
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_INTERVAL_EVENT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'INTERVAL_EVENT',
         'time': 'DV_DATE_TIME',
         'state': '_ask_node_', // ITEM_STRUCTURE
         'data': '_ask_node_', // ITEM_STRUCTURE
         'width': 'DV_DURATION',
         'math_function': 'DV_CODED_TEXT'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ITEM_SINGLE_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ITEM_SINGLE',
         'item': 'ELEMENT'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ITEM_LIST_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ITEM_LIST',
         'items': 'ELEMENT'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ITEM_TABLE_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ITEM_TABLE',
         'rows': 'CLUSTER'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ITEM_TREE_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ITEM_TREE',
         'items': '_ask_node_' // CLUSTER, ELEMENT
      ]
      /*
      println "Tree children"
      node.items.each {
         println it.name() +" "+it.'@xsi:type'
      }
      */
      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_CLUSTER_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'CLUSTER',
         'items': '_ask_node_' // CLUSTER, ELEMENT
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }

   private void process_ELEMENT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def attributes = [
         '_class': 'ELEMENT', // metadata to calculate the instance paths
         'value': '_ask_node_', // any data value
         'null_flavour': 'DV_CODED_TEXT'
      ]

      keepProcessing(node, templateId, path, archetypeId, archetypePath, owner, indexes, attributes, multiple_index, parentInstancePath, parentClass)
   }


   // DVs

   private void process_DV_TEXT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths text "+ paths

      /*
      * WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_TEXT.
      <value xsi:type="DV_TEXT">
        <value>Right arm</value>
      </value>
      */

      indexes << new DvTextIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         node.value.text(),
        rmTypeName:    'DV_TEXT',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_CODED_TEXT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths coded text "+ paths

      /*
      * WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_CODED_TEXT.
      <value xsi:type="DV_CODED_TEXT">
        <value>Right arm</value>
        <defining_code>
          <terminology_id>
            <value>local</value>
          </terminology_id>
          <code_string>at0025</code_string>
        </defining_code>
      </value>
      */

      // Throws an exception if the node has xsi:type="..." attribute,
      // because the xmlns:xsi is not defined in the node.
      //println "DvCodedTextIndex "+ groovy.xml.XmlUtil.serialize(node)

      indexes << new DvCodedTextIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         node.value.text(),
        code:          node.defining_code.code_string.text(),
        terminologyId: node.defining_code.terminology_id.value.text(),
        rmTypeName:    'DV_CODED_TEXT',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_DATE_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths date "+ paths

      //WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_DATE_TIME.
      //<time>
      //  <value>20070920</value>
      //</time>
      indexes << new DvDateIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         DateParser.tryParse(node.value.text()),
        rmTypeName:    'DV_DATE',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_DATE_TIME_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths date time "+ paths

      /*
      * WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_DATE_TIME.
      <time>
        <value>20070920T104614,156+0930</value>
      </time>
      */
      indexes << new DvDateTimeIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         DateParser.tryParse(node.value.text()),
        rmTypeName:   'DV_DATE_TIME',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_QUANTITY_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths quantity "+ paths

      /*
      * WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_QUANTITY
      <value xsi:type="DV_QUANTITY">
        <magnitude>120</magnitude>
        <units>mm[Hg]</units>
      </value>
      */
      indexes << new DvQuantityIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        magnitude:     new Double( node.magnitude.text() ),
        units:         node.units.text(),
        rmTypeName:    'DV_QUANTITY',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_COUNT_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths count "+ paths.instanceTemplatePath

      /*
      <value xsi:type="DV_COUNT">
        <magnitude>120</magnitude>
      </value>
      */
      indexes << new DvCountIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        magnitude:     new Long( node.magnitude.text() ),
        rmTypeName:    'DV_COUNT',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_DURATION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths duration "+ paths
      //println "DV_DURATION "+ node.toString()
      /*
      * WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_QUANTITY
      <value xsi:type="DV_DURATION">
        <value>PT30M</value> // 30 mins
      </value>
      */
      indexes << new DvDurationIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         node.value.text(),
        rmTypeName:    'DV_DURATION',
        instanceTemplatePath: paths.instanceTemplatePath
        //magnitude: new Double( node.magnitude.text() ) // TODO: parse duration in seconds using Joda time.
      )
   }

   private void process_DV_BOOLEAN_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths boolean "+ paths

      // WARNING: el nombre de la tag contenedor puede variar segun el nombre del atributo de tipo DV_TEXT.
      //<value xsi:type="DV_BOOLEAN">
      //   <value>true</value>
      // </value>
      indexes << new DvBooleanIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         new Boolean(node.value.text()),
        rmTypeName:    'DV_BOOLEAN',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_IDENTIFIER_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths identifier "+ paths

      indexes << new DvIdentifierIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        identifier:    node.id.text(),
        type:          node.type.text(),
        issuer:        node.issuer.text(),
        assigner:      node.assigner.text(),
        rmTypeName:    'DV_IDENTIFIER',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_DV_ORDINAL_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths identifier "+ paths

      //<value xsi:type="DV_ORDINAL">
      // <value>234</value>
      // <symbol>
      //   <value>Right arm</value>
      //   <defining_code>
      //    <terminology_id>
      //      <value>local</value>
      //    </terminology_id>
      //    <code_string>at0025</code_string>
      //   </defining_code>
      // </symbol>
      //</value>
      indexes << new DvOrdinalIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         new Integer( node.value.text() ),
        symbol_value:  node.symbol.value.text(),
        symbol_code:   node.symbol.defining_code.code_string.text(),
        symbol_terminology_id: node.symbol.defining_code.terminology_id.value.text(),
        rmTypeName:    'DV_ORDINAL',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   // for ACTIVITY.timing
   private void process_DV_PARSABLE_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths parsable "+ paths

      /*
      <value xsi:type="DV_PARSABLE">
        <value>20170629</value>
        <formalism>iso8601</formalism>
      </value>
      */

      indexes << new DvParsableIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        value:         node.value.text(),
        formalism:     node.formalism.text().toLowerCase(), // formalism is always lower case, this is to avoid case issues from the client
        rmTypeName:    'DV_PARSABLE',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private void process_LOCATABLE_REF_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

      indexes << new LocatableRefIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,

        locatable_ref_path: node.path.text(),
        namespace:     node.namespace.text(),
        type:          node.type.text(),
        value:         node.id.value.text(),

        rmTypeName:    'LOCATABLE_REF',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   // for String RM attributes like ACTIVITY.action_archetype_id
   private void process_String_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

      indexes << new StringIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,

        value:         node.text(),
        rmTypeName:    'String',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }


   private void process_DV_PROPORTION_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)
      //println "paths proportion "+ paths

      /**
      * <xs:complexType name="DV_ORDERED" abstract="true">
           <xs:complexContent>
               <xs:extension base="DATA_VALUE">
                  <xs:sequence>
                      <xs:element name="normal_range" type="DV_INTERVAL" minOccurs="0"/>
                      <xs:element name="other_reference_ranges" type="REFERENCE_RANGE" minOccurs="0" maxOccurs="unbounded"/>
                      <xs:element name="normal_status" type="CODE_PHRASE" minOccurs="0"/>
                  </xs:sequence>
               </xs:extension>
           </xs:complexContent>
       </xs:complexType>
      * <xs:complexType name="DV_QUANTIFIED" abstract="true">
           <xs:complexContent>
               <xs:extension base="DV_ORDERED">
                  <xs:sequence>
                      <xs:element name="magnitude_status" type="xs:string" minOccurs="0"/>
                  </xs:sequence>
               </xs:extension>
           </xs:complexContent>
       </xs:complexType>
      * <xs:complexType name="DV_AMOUNT">
           <xs:complexContent>
               <xs:extension base="DV_QUANTIFIED">
                  <xs:sequence>
                      <xs:element name="accuracy" type="xs:float" minOccurs="0" default="-1.0"/>
                      <xs:element name="accuracy_is_percent" type="xs:boolean" minOccurs="0"/>
                  </xs:sequence>
               </xs:extension>
           </xs:complexContent>
       </xs:complexType>
      * <xs:complexType name="DV_PROPORTION">
           <xs:complexContent>
               <xs:extension base="DV_AMOUNT">
                  <xs:sequence>
                      <xs:element name="numerator" type="xs:float"/>
                      <xs:element name="denominator" type="xs:float"/>
                      <xs:element name="type" type="PROPORTION_KIND"/>
                      <xs:element name="precision" type="xs:int" default="-1" minOccurs="0"/>
                  </xs:sequence>
               </xs:extension>
           </xs:complexContent>
       </xs:complexType>


       <value xsi:type="DV_PROPORTION">
         <normal_range>...</normal_range>
         <other_reference_ranges>...</other_reference_ranges>
         <normal_status>...</normal_status>
         <magnitude_status>=</magnitude_status>
         <accuracy>0.5</accuracy>
         <accuracy_is_percent>false</accuracy_is_percent>
         <numerator></numerator>
         <denominator></denominator>
         <type></type>
         <precision></precision>
       </value>
      */

      // 0 = pk_ration: num and denom may be any value so are float
      int type = ( (node.type.text()) ? (new Integer(node.type.text())) : 0 )

      //println "DvPropotion parse type: "+ type

      // Parsing numerator and denominator considering the type
      // Some checks are done here instead as constraints of DvProportionIndex,
      // that's ok because the checks are done for parsing the data correctly,
      // not to validate the data itself.
      def numerator
      def denominator
      switch (type)
      {
        case 0: // pk_ratio = 0 num and denom may be any value
          numerator = new Double(node.numerator.text())
          denominator = new Double(node.denominator.text())
        break
        case 1: // pk_unitary = 1 denominator must be 1
          numerator = new Double(node.numerator.text())
          if (node.denominator.text() != "1") throw new Exception("DV_PROPORTION For proportion kind unitary, denominator should be 1")
          denominator = 1
        break
        case 2: // pk_percent = 2 denominator is 100, numerator is understood as a percentage
          numerator = new Double(node.numerator.text())
          if (node.denominator.text() != "100") throw new Exception("DV_PROPORTION For proportion kind percent, denominator should be 100")
          denominator = 100
        break
        case 3: // pk_fraction = 3 num and denum are integral and the presentation method used a slash e.g. 1/2
          numerator = new Double(node.numerator.text())
          denominator = new Double(node.denominator.text())
          if (!isIntegral(numerator)) throw new Exception("DV_PROPORTION For proportion kind fraction, numerator should be intetral and is ${numerator.getClass()}")
          if (!isIntegral(denominator)) throw new Exception("DV_PROPORTION For proportion kind fraction, denominator should be intetral and is ${denominator.getClass()}")
        break
        case 4: // pk_integer_fraction = 4 num and denom are integral, usual presentation is n/d; if numerator > denominator, display as “a b/c”, i.e. the integer part followed by the remaining fraction part, e.g. 1 1/2;
          numerator = new Double(node.numerator.text())
          denominator = new Double(node.denominator.text())
          if (!isIntegral(numerator)) throw new Exception("DV_PROPORTION For proportion kind integer fraction, numerator should be intetral and is ${numerator.getClass()}")
          if (!isIntegral(denominator)) throw new Exception("DV_PROPORTION For proportion kind integer fraction, denominator should be intetral and is ${denominator.getClass()}")
        break
        default:
          throw new Exception("DV_PROPORTION type '$type' not valid")
      }

      //println "DvPropotion parse: "+ numerator +"/"+ denominator

      indexes << new DvProportionIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        numerator:     numerator,
        denominator:   denominator,
        type:          type,
        precision:     ((node.precision.text()) ? new Integer(node.precision.text()) : -1),
        rmTypeName:    'DV_PROPORTION',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   private boolean isIntegral(double num) {
      return (Math.floor(num1) == num1)
   }

   private void process_DV_MULTIMEDIA_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

      /*
      <value xsi:type="DV_MULTIMEDIA">
        <alternate_text>blablabla</alternate_text>
        <uri> -- uri is alternative to data
          <value></value>
        </uri>
        <data>gfsdfhgshd554ydtfh45hde45rth</data>
        <media_type>
          <terminology_id>
            <value>IANA</value>
          </terminology_id>
          <code_string>image/jpeg</code_string>
        </media_type>
        <size>345345</size>
      </value>
      */

      String media_type = node.media_type.code_string.text()

      // media comes already encoded like image/jpeg;base64, decode to save with
      // removing the base64 from the media type, we consider the client doesn't
      // re-encode for the XML or JSON, so we always decode and get the correct
      // data if: 1. data is encoded for XML or JSON transfer, or 2. data is
      // already encoded, and just update the media type
      if (media_type.toLowerCase().endsWith('base64'))
      {
         media_type -= ';base64'
      }

      indexes <<  new DvMultimediaIndex(
        templateId:    templateId,
        archetypeId:   paths.rootArchetype,
        path:          paths.templatePath,
        archetypePath: paths.archetypePath,
        owner:         owner,
        alternateText: node.alternate_text.text(),
        data:          node.data.text().decodeBase64(), // byte[]
        uri:           node.uri.value.text(),
        mediaType:     media_type, // is a CODE_PHRASE but we don't save the terminology, it will always be IANA.
        size:          new Integer( node.size.text() ), // should be the size of the original decoded data, we trust the source
        rmTypeName:   'DV_MULTIMEDIA',
        instanceTemplatePath: paths.instanceTemplatePath
      )
   }

   // Process all interval types
   void process_DV_INTERVAL_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, 
      String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

      //println "paths count "+ paths.instanceTemplatePath

      /*
      <value xsi:type="DV_INTERVAL">
         <lower xsi:type="DV_COUNT"> | DV_QUANTITY | DV_DATE_TIME
            <magnitude>3</magnitude>
         </lower>
         <upper xsi:type="DV_COUNT">
            <magnitude>50</magnitude>
         </upper>
         <lower_unbounded>false</lower_unbounded>
         <upper_unbounded>false</upper_unbounded>
      </value>
      */
      
      // TODO: https://github.com/ppazos/EHRServerNG/issues/102
   }

   // TODO: DV_URI and DV_EHR_URI indexers https://github.com/ppazos/EHRServerNG/issues/101
   void process_DV_URI_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, 
      String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

   }

   void process_DV_EHR_URI_index(
      GPathResult node,
      String templateId, String path,
      String archetypeId, String archetypePath,
      CompositionIndex owner, List indexes, int multiple_index, String parentInstancePath, 
      String parentClass)
   {
      def paths = getChildPathsAndRootArchetype(node, path, archetypePath, archetypeId, multiple_index, parentInstancePath, parentClass)

   }
}
