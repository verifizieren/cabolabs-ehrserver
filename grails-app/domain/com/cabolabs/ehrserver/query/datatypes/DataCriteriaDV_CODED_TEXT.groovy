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
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cabolabs.ehrserver.query.datatypes

import com.cabolabs.ehrserver.query.DataCriteria
import com.cabolabs.openehr.opt.manager.OptManager
import com.cabolabs.ehrserver.ehr.clinical_documents.ArchetypeIndexItem
import org.springframework.web.context.request.RequestContextHolder
import com.cabolabs.openehr.terminology.TerminologyParser
import com.cabolabs.ehrserver.conf.TerminologyId
import com.cabolabs.ehrserver.conf.ConfigurationItem

class DataCriteriaDV_CODED_TEXT extends DataCriteria {

   static String indexType = 'DvCodedTextIndex'

   // Comparison values
   List codeValue
   String terminologyIdValue
   String valueValue

   // Comparison operands
   String codeOperand
   String terminologyIdOperand
   String valueOperand

   boolean codeNegation = false
   boolean terminologyIdNegation = false
   boolean valueNegation = false

   DataCriteriaDV_CODED_TEXT()
   {
      rmTypeName = 'DV_CODED_TEXT'
      alias = 'dcti'
   }

   static hasMany = [codeValue: String]

   static constraints = {
      codeOperand(nullable:true)
      terminologyIdOperand(nullable:true)
      valueOperand(nullable:true)
      terminologyIdValue(nullable:true)
      valueValue(nullable:true)
   }
   static mapping = {
      valueValue column: "dv_codedtext_value"
      terminologyIdValue column: "dv_codedtext_terminology_id"
   }

   /**
    * Metadata that defines the types of criteria supported to search
    * by conditions over DV_CODED_TEXT.
    * @return
    */
   static List criteriaSpec(String archetypeId, String path, boolean returnCodes = true)
   {
      def spec = [
        [
          code: [
            eq: 'value',     // operand eq can be applied to attribute code and the reference value is a single value
            in_list: 'list'  // operand in_list can be applied to attribute code and the reference value is a list of values

            // TODO: there is a dependence between code and terminologyId constraints, if in_snomed_exp is selected,
            //       I want codes to terminologyId to be set to this list, or I can send it always and avoid processing it on the ui.
            // International Edition 20170131
            // International Edition 20160131
            // International Edition 20160731
            // Spanish Edition 20160430
            // Spanish Edition 20160430 + SNS 20160430
            // Spanish Edition 20161031
            // Spanish Edition 20161031 + SNS 20161031
            //in_snomed_exp: 'snomed_exp' // added below only if the snquery is enabled
          ],
          terminologyId: [
            eq: 'value',
            contains: 'value'
          ]
        ],
        [
          value: [contains: 'value', eq: 'value']
        ]
      ]

      // add the in_snomed operator only if the snquery is enabled
      if (ConfigurationItem.findByKey('ehrserver.query.snquery.enabled')?.typedValue)
      {
         spec.code.in_snomed_exp = 'snomed_exp'
      }

      if (returnCodes)
      {
         def optMan = OptManager.getInstance()

         // List of valid codes for the code criteria
         // The path received points to the DV_CODED_TEXT, the codes are in the child CODE_PRHASE
         def codes = [:]

         def lang = RequestContextHolder.currentRequestAttributes().session.lang
         def namespace = RequestContextHolder.currentRequestAttributes().session.organization.uid

         // 1. codeList can be empty if the archetype doesn't have a constraint.
         // 2. for DV_TEXT we generate DV_CODED_TEXT index to support inheritance,
         // but if the OPT doesn't have a DV_CODED_TEXT, the path will return a null node.
         // https://github.com/ppazos/cabolabs-ehrserver/issues/528

         def terminologyId = 'local'

         // There could be multiple constraints on the same path as alternatives
         // TODO: test if we need to display more than one criteria builder if there are alternative constraints
         def constraints = optMan.getNodes(archetypeId, path + '/defining_code', namespace)

         //println constraint
         //println constraint.reference // ac0001
         //println constraint.terminologyId
         //println constraint.terminologyRef

         // to get terms from the openehr terminology
         def terminology = TerminologyParser.getInstance()

         if (constraints)
         {
            def constraint = constraints.find{ it.type == 'C_CODE_PHRASE' }
            if (constraint) // C_CODE_PHRASE is the only type that has codeList, the constraint can be also COSTRAINT_REF or or C_CODE_REFERENCE.
            {
               if (constraint.terminologyId == 'local')
               {
                  constraint.codeList.each { code ->
                     codes[code] = optMan.getText(archetypeId, code, lang, namespace)
                  }
               }
               else if (constraint.terminologyId == 'openehr')
               {
                  // resolve against terminology!!!

                  // using TerminologyParser from openEHR-OPT
                  constraint.codeList.each { code ->
                     codes[code] = terminology.getRubric(lang, code)
                  }

                  terminologyId = 'openehr'
               }
            }
            else
            {
               // nodes is a list of CCodePhrase if any are found
               def nodes = optMan.getNodesByDataPath(archetypeId, path + '/defining_code', namespace)
               if (nodes)
               {
                  nodes.each { ccodephrase ->
                     if (ccodephrase.terminologyId == 'local')
                     {
                        ccodephrase.codeList.each { code ->
                           codes[code] = optMan.getText(archetypeId, code, lang, namespace)
                        }
                     }
                     else if (ccodephrase.terminologyId == 'openehr')
                     {
                        // resolve against terminology!!!

                        // using TerminologyParser from openEHR-OPT
                        ccodephrase.codeList.each { code ->
                           codes[code] = terminology.getRubric(lang, code)
                        }

                        terminologyId = 'openehr'
                     }
                  }
               }
            }
         }


         // if it starts with underscore, do not process on the ui
         /* currently we dont need the versions on the query builder since versions are used to get the name/rubric, and queries use just the conceptid
         spec[0].terminologyId._snomed = ['SNOMED-CT(International Edition 20170131)'         : 'SNOMED-CT(International Edition 20170131)',
                                          'SNOMED-CT(International Edition 20160131)'         : 'SNOMED-CT(International Edition 20160131)',
                                          'SNOMED-CT(International Edition 20160731)'         : 'SNOMED-CT(International Edition 20160731)',
                                          'SNOMED-CT(Spanish Edition 20160430)'               : 'SNOMED-CT(Spanish Edition 20160430)',
                                          'SNOMED-CT(Spanish Edition 20160430 + SNS 20160430)': 'SNOMED-CT(Spanish Edition 20160430 + SNS 20160430)',
                                          'SNOMED-CT(Spanish Edition 20161031)'               : 'SNOMED-CT(Spanish Edition 20161031)',
                                          'SNOMED-CT(Spanish Edition 20161031 + SNS 20161031)': 'SNOMED-CT(Spanish Edition 20161031 + SNS 20161031)']
         */
         // add the only if the snquery is enabled
         if (ConfigurationItem.findByKey('ehrserver.query.snquery.enabled')?.typedValue)
         {
            spec[0].terminologyId._snomed = ['SNOMED-CT': 'SNOMED-CT'] // this needs to be set to the terminology when in_snomed_exp operator is selected.
         }

         if (codes.size() > 0)
         {
           spec[0].code.codes = codes

           // if the terms are defined in the archetype, the terminology is 'local' or 'openehr'
           spec[0].terminologyId.codes = [(terminologyId): terminologyId]
         }
         else if (path.endsWith('/null_flavour')) // show valid null flavour codes
         {
            // TODO: check if the opt has constraints for this node to use only those codes
            // TODO: support getting this from i18n openehr terminology
            /*
            <group name="null flavours">
             <concept id="271" rubric="no information"/>
             <concept id="253" rubric="unknown"/>
             <concept id="272" rubric="masked"/>
             <concept id="273" rubric="not applicable"/>
            </group>
            */
            spec[0].code.codes = [
               253: 'unknown',
               271: 'no information',
               272: 'masked',
               273: 'not applicable'
            ]
            spec[0].terminologyId.codes = ['openehr': 'openehr'] // if the terms are defined in the archetype, the terminology is local
         }
         else if (path == '/context/setting') // TODO: check archetype is for COMPOSITION
         {
            // Provide setting codes from openEHR terminology
            // TODO: this should be provided by configuration and be per organization since the openEHR terminology is open for this.
            // TODO: i18n
            spec[0].code.codes = [
               225: 'home',
               227: 'emergency care',
               228: 'primary medical care',
               229: 'primary nursing care',
               230: 'primary allied health care',
               231: 'midwifery care',
               232: 'secondary medical care',
               233: 'secondary nursing care',
               234: 'secondary allied health care',
               235: 'complementary health care',
               236: 'dental care',
               237: 'nursing home care',
               238: 'other care'
            ]
            spec[0].terminologyId.codes = ['openehr': 'openehr']
         }
         else
         {
            // https://github.com/ppazos/cabolabs-ehrserver/issues/154
            def idef = ArchetypeIndexItem.findByArchetypeIdAndPath(archetypeId, path)
            if (idef && idef.terminologyRef)
            {
               // terminology:WHO?subset=ATC&amp;language=en-GB
               // WHO
               def terminology_ref = idef.terminologyRef.split('\\?')[0].split(':')[1]

               spec[0].terminologyId.codes = [(terminology_ref): terminology_ref]
            }
            else // there are no terminologies in the OPT, get available ones
            {
               // TODO: get terminologies from DB
               //spec[0].terminologyId.codes = ['SNOMED-CT': 'SNOMED-CT', 'LOINC': 'LOINC', 'ICD10': 'ICD10']
               spec[0].terminologyId.codes = [:]
               TerminologyId.list().name.each { name ->
                  spec[0].terminologyId.codes << [(name): name]
               }
            }
         }
      }

      return spec
   }

   static List attributes()
   {
      return ['value', 'code', 'terminologyId']
   }

   static List functions()
   {
      return []
   }

   String toString()
   {
      return this.getClass().getSimpleName() +": "+ this.codeOperand +" "+ this.codeValue.toString() +" "+ this.terminologyIdOperand +" "+ this.terminologyIdValue
   }

   boolean containsFunction()
   {
      return false
   }
}
