/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.commonwl.view.workflow;

import org.commonwl.view.github.GithubDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;

/**
 * Stores workflow objects in the database
 */
public interface WorkflowRepository extends PagingAndSortingRepository<Workflow, String> {

    /**
     * Finds a workflow model in the database based on where it was retrieved from
     * @param retrievedFrom Details of where the workflow is from
     * @return The workflow model
     */
    Workflow findByRetrievedFrom(GithubDetails retrievedFrom);

    /**
     * Paged request to get workflows of a specific status
     * @param status The status of the workflows to be retrieved
     * @param pageable The details of the page to be retrieved
     * @return The requested page of workflows
     */
    Page<Workflow> findByCwltoolStatusOrderByRetrievedOnDesc(Workflow.Status status, Pageable pageable);


    /**
     * Finds successful workflows where a string is within the label or doc
     * @param search The string to search for
     * @param pageable The details of the page to be retrieved
     */
    @Query("{cwltoolStatus:'SUCCESS', $or: [{label: {$regex: '.*?0.*', $options: 'i'}}, {doc: {$regex: '.*?0.*', $options: 'i'}}]}")
    Page<Workflow> findByLabelContainingOrDocContaining(String search, Pageable pageable);
}