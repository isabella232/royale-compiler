/**
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
/**
 * Generated by Apache Royale Compiler from E.as
 * E
 *
 * @fileoverview
 *
 * @suppress {missingRequire|checkTypes|accessControls}
 */

goog.provide('E');




/**
 * @constructor
 */
E = function() {
};


/**
 * Prevent renaming of class. Needed for reflection.
 */
goog.exportSymbol('E', E);


/**
 * @export
 * @param {boolean} b
 * @return {number}
 */
E.a = function(b) {
  return C.a(false);
};


/**
 * Metadata
 *
 * @type {Object.<string, Array.<Object>>}
 */
E.prototype.ROYALE_CLASS_INFO = { names: [{ name: 'E', qName: 'E', kind: 'class' }] };



/**
 * Reflection
 *
 * @return {Object.<string, Function>}
 */
E.prototype.ROYALE_REFLECTION_INFO = function () {
  return {
    variables: function () {return {};},
    accessors: function () {return {};},
    methods: function () {
      return {
        'E': { type: '', declaredBy: 'E'},
        '|a': { type: 'int', declaredBy: 'E', parameters: function () { return [ { index: 1, type: 'Boolean', optional: false } ]; }}
      };
    }
  };
};
/**
 * @const
 * @type {number}
 */
E.prototype.ROYALE_REFLECTION_INFO.compileFlags = 8;
