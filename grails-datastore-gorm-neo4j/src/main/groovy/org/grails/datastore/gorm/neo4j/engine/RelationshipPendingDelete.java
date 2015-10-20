/*
 * Copyright 2015 original authors
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
package org.grails.datastore.gorm.neo4j.engine;

import org.grails.datastore.gorm.neo4j.GraphPersistentEntity;
import org.grails.datastore.mapping.core.impl.PendingInsertAdapter;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a pending relationship delete
 *
 * @author Stefan
 */
public class RelationshipPendingDelete extends PendingInsertAdapter<Object, Long> {

    private static Logger log = LoggerFactory.getLogger(RelationshipPendingDelete.class);

    private String relType;
    private CypherEngine cypherEngine;
    private EntityAccess target;

    public RelationshipPendingDelete(EntityAccess source, String relType, EntityAccess target, CypherEngine cypherEngine) {
        super(source.getPersistentEntity(), -1l, source.getEntity(), source);
        this.target = target;
        this.cypherEngine = cypherEngine;
        this.relType = relType;
    }

    @Override
    public void run() {
        String labelsFrom = ((GraphPersistentEntity)getEntity()).getLabelsAsString();
        String labelsTo = null;
        String cypher;

        List params =  new ArrayList(2);
        params.add(getEntityAccess().getIdentifier());
        if (target!=null) {
            params.add(target.getIdentifier());
            labelsTo = ((GraphPersistentEntity)target.getPersistentEntity()).getLabelsAsString();
            cypher = String.format("MATCH (from%s {__id__: {1}})-[r:%s]->(to%s {__id__: {2}}) DELETE r", labelsFrom, relType, labelsTo);
        } else {
            cypher = String.format("MATCH (from%s {__id__: {1}})-[r:%s]->() DELETE r", labelsFrom, relType);

        }
        cypherEngine.execute(cypher, params);
    }

}
