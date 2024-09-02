/*
 * Copyright 2023 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.manager.integration.datatable;

import org.secretflow.secretpad.common.constant.DomainDatasourceConstants;
import org.secretflow.secretpad.common.enums.DataSourceTypeEnum;
import org.secretflow.secretpad.common.enums.DataTableTypeEnum;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.errorcode.DatatableErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.kuscia.v1alpha1.service.impl.KusciaGrpcClientAdapter;
import org.secretflow.secretpad.manager.integration.model.DatatableDTO;
import org.secretflow.secretpad.manager.integration.model.DatatableListDTO;
import org.secretflow.secretpad.manager.integration.node.SearchTargetNodeManager;
import org.secretflow.secretpad.persistence.entity.FeatureTableDO;
import org.secretflow.secretpad.persistence.repository.FeatureTableRepository;

import org.secretflow.v1alpha1.kusciaapi.Domaindata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.CollectionUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.secretflow.secretpad.common.constant.Constants.STATUS_AVAILABLE;
import static org.secretflow.secretpad.common.constant.Constants.STATUS_UNAVAILABLE;

/**
 * Manager datatable operation
 *
 * @author yansi
 * @date 2023/5/23
 */
public class DatatableManager extends AbstractDatatableManager {
    private final static Logger LOGGER = LoggerFactory.getLogger(DatatableManager.class);
    /**
     * Domain data service blocking stub
     */
    private final KusciaGrpcClientAdapter kusciaGrpcClientAdapter;
    private final FeatureTableRepository featureTableRepository;
    @Value("${secretpad.platform-type}")
    private String plaformType;

    @Value("${secretpad.node-id}")
    private String localNodeId;

    public DatatableManager(KusciaGrpcClientAdapter kusciaGrpcClientAdapter, FeatureTableRepository featureTableRepository) {
        this.featureTableRepository = featureTableRepository;
        this.kusciaGrpcClientAdapter = kusciaGrpcClientAdapter;
    }

    /**
     * local inst query
     **/
    @Override
    public Optional<DatatableDTO> findById(DatatableDTO.NodeDatatableId nodeDatatableId) {

        Domaindata.QueryDomainDataRequest queryDomainDataRequest = Domaindata.QueryDomainDataRequest.newBuilder()
                .setData(Domaindata.QueryDomainDataRequestData.newBuilder()
                        .setDomainId(nodeDatatableId.getNodeId())
                        .setDomaindataId(nodeDatatableId.getDatatableId())
                        .build())
                .build();
        Domaindata.QueryDomainDataResponse response;
        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType))) {
            response = kusciaGrpcClientAdapter.queryDomainData(queryDomainDataRequest, nodeDatatableId.getNodeId());
        } else {
            response = kusciaGrpcClientAdapter.queryDomainData(queryDomainDataRequest);
        }

        if (response == null) {
            LOGGER.error("lock up from kusciaapi failed ,response is null");
            throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
        }
        if (response.getStatus().getCode() != 0) {
            LOGGER.error("lock up from kusciaapi failed: code={}, message={}, request={}",
                    response.getStatus().getCode(), response.getStatus().getMessage(), JsonUtils.toJSONString(nodeDatatableId));
            throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
        }

        return Optional.of(DatatableDTO.fromDomainData(response.getData()));
    }


    @Override
    public List<Domaindata.DomainData> findByIdsBase(List<DatatableDTO.NodeDatatableId> nodeDatatableIds, SearchTargetNodeManager nodeService) {
        final List<Domaindata.DomainData> domainDataList = new ArrayList<>();
        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType))) {
            String targetNodeId = nodeService.getTargetNodeId(nodeDatatableIds.get(0).getNodeId(), null);
            Domaindata.BatchQueryDomainDataRequest nodeRequest = Domaindata.BatchQueryDomainDataRequest.
                    newBuilder().addAllData(
                            nodeDatatableIds.stream().map(
                                            it -> Domaindata.QueryDomainDataRequestData.newBuilder()
                                                    .setDomainId(it.getNodeId())
                                                    .setDomaindataId(it.getDatatableId())
                                                    .build())
                                    .collect(Collectors.toList()))
                    .build();

            Domaindata.BatchQueryDomainDataResponse responses = kusciaGrpcClientAdapter.batchQueryDomainData(nodeRequest, targetNodeId);
            if (responses.getStatus().getCode() != 0) {
                LOGGER.error("findByIds lock up from kusciaapi failed: code={}, message={}, request={}",
                        responses.getStatus().getCode(), responses.getStatus().getMessage(), JsonUtils.toJSONString(nodeDatatableIds));
                throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
            }

            if (!CollectionUtils.isEmpty(responses.getData().getDomaindataListList())) {
                domainDataList.addAll(responses.getData().getDomaindataListList());
            }

        } else {
            Domaindata.BatchQueryDomainDataRequest batchQueryDomainDataRequest = Domaindata.BatchQueryDomainDataRequest.newBuilder()
                    .addAllData(nodeDatatableIds.stream().map(
                            it -> Domaindata.QueryDomainDataRequestData.newBuilder()
                                    .setDomainId(it.getNodeId()).setDomaindataId(it.getDatatableId()).build()).collect(Collectors.toList()))
                    .build();
            Domaindata.BatchQueryDomainDataResponse responses = kusciaGrpcClientAdapter.batchQueryDomainData(batchQueryDomainDataRequest);
            if (responses.getStatus().getCode() != 0) {
                LOGGER.error("findByIds lock up from kusciaapi failed: code={}, message={}, request={}",
                        responses.getStatus().getCode(), responses.getStatus().getMessage(), JsonUtils.toJSONString(nodeDatatableIds));
                throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
            }

            domainDataList.addAll(responses.getData().getDomaindataListList());
            LOGGER.debug("request table  responses {} ", responses);
        }
        return domainDataList;
    }

    @Override
    public Map<DatatableDTO.NodeDatatableId, DatatableDTO> findByIds(List<DatatableDTO.NodeDatatableId> nodeDatatableIds, SearchTargetNodeManager nodeService) {
        List<Domaindata.DomainData> domainDataList = this.findByIdsBase(nodeDatatableIds, nodeService);
        Map<DatatableDTO.NodeDatatableId, DatatableDTO> result = domainDataList.stream().map(DatatableDTO::fromDomainData)
                .collect(Collectors.toMap(it -> DatatableDTO.NodeDatatableId.from(it.getNodeId(), it.getDatatableId()), Function.identity()));

        LOGGER.debug("request table  result {} ", result);
        LOGGER.info("request table size={}, and response table size={} ", nodeDatatableIds.size(), result.size());
        return result;
    }

    @Override
    public  List<Domaindata.DomainData> findByIdGroup(List<DatatableDTO.NodeDatatableId> nodeDatatableIds , SearchTargetNodeManager searchManager){
        final List<Domaindata.DomainData> domainDataList = new ArrayList<>();
        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType))) {
            /* set target node  */
            nodeDatatableIds.forEach(it -> it.setNodeId(searchManager.getTargetNodeId(it.getNodeId(), null)));
            /* group by target node */
            Map<String, List<DatatableDTO.NodeDatatableId>> nodeIdMap = nodeDatatableIds.stream().collect(Collectors.groupingBy(DatatableDTO.NodeDatatableId::getNodeId));
            nodeIdMap.forEach((key, value) -> {
                Domaindata.BatchQueryDomainDataRequest nodeRequest = Domaindata.BatchQueryDomainDataRequest.
                        newBuilder().addAllData(value.stream().map(it -> Domaindata.QueryDomainDataRequestData.newBuilder()
                                .setDomainId(it.getNodeId())
                                .setDomaindataId(it.getDatatableId()).build()).collect(Collectors.toList()))
                        .build();

                Domaindata.BatchQueryDomainDataResponse responses = kusciaGrpcClientAdapter.batchQueryDomainData(nodeRequest, key);
                if (responses.getStatus().getCode() != 0) {
                    LOGGER.error("findByIds lock up from kusciaapi failed: code={}, message={}, request={}",
                            responses.getStatus().getCode(), responses.getStatus().getMessage(), JsonUtils.toJSONString(nodeDatatableIds));
                    throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
                }

                if (!CollectionUtils.isEmpty(responses.getData().getDomaindataListList())) {
                    domainDataList.addAll(responses.getData().getDomaindataListList());
                }
            });
        } else {
            Domaindata.BatchQueryDomainDataRequest batchQueryDomainDataRequest = Domaindata.BatchQueryDomainDataRequest.newBuilder()
                    .addAllData(nodeDatatableIds.stream().map(
                            it -> Domaindata.QueryDomainDataRequestData.newBuilder()
                                    .setDomainId(it.getNodeId()).setDomaindataId(it.getDatatableId()).build()).collect(Collectors.toList()))
                    .build();
            Domaindata.BatchQueryDomainDataResponse responses = kusciaGrpcClientAdapter.batchQueryDomainData(batchQueryDomainDataRequest);
            if (responses.getStatus().getCode() != 0) {
                LOGGER.error("findByIds lock up from kusciaapi failed: code={}, message={}, request={}",
                        responses.getStatus().getCode(), responses.getStatus().getMessage(), JsonUtils.toJSONString(nodeDatatableIds));
                throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
            }

            domainDataList.addAll(responses.getData().getDomaindataListList());
            LOGGER.debug("request table  responses {} ", responses);
        }
        return domainDataList;
    }




    /***
     *  nodes a1 a2 b1 b2 in same project,show all data tables
     *  a1 <-> b1
     *  a2 <-> b2
     *  when you are in inst a , map b1,b2 to a1,a2, then query from node a1,a2
     *  when you are in inst b , map a1,a2 to b1,b2, then query from node b1,b2
     */
    @Override
    public Map<DatatableDTO.NodeDatatableId, DatatableDTO> findByIdsFromProjectConfig(List<DatatableDTO.NodeDatatableId> nodeDatatableIds, SearchTargetNodeManager searchManager) {
        final List<Domaindata.DomainData> domainDataList = findByIdGroup(nodeDatatableIds, searchManager);
        Map<DatatableDTO.NodeDatatableId, DatatableDTO> result = domainDataList.stream().map(DatatableDTO::fromDomainData)
                .collect(Collectors.toMap(it -> DatatableDTO.NodeDatatableId.from(it.getNodeId(), it.getDatatableId()), Function.identity()));

        LOGGER.debug("request table  result {} ", result);
        LOGGER.info("request table size={}, and response table size={} ", nodeDatatableIds.size(), result.size());
        return result;
    }

    @Override
    public DatatableListDTO findByNodeId(
            String nodeId,
            Integer pageSize,
            Integer pageNumber,
            String statusFilter,
            String datatableNameFilter,
            List<String> types) {
        LOGGER.info("Find datatable with kuscia api with node id = {}, filter by usermanul vendor.", nodeId);
        List<DatatableDTO> datatableDTOList = findByNodeId(nodeId, DATA_VENDOR_MANUAL);
        //feature table
        List<DatatableDTO> featureTableDTOList = findHttpFeatureTableByNodeId(nodeId);
        if (!CollectionUtils.isEmpty(featureTableDTOList)) {
            datatableDTOList.addAll(featureTableDTOList);
        }
        LOGGER.info("The datatable list len = {}, now filter by status = {}", datatableDTOList.size(), statusFilter);
        datatableDTOList = filterByStatus(datatableDTOList, statusFilter);
        LOGGER.info("After filter by status the datatable list len = {}, now filter by datatable name = {}", datatableDTOList.size(), datatableNameFilter);
        datatableDTOList = filterByDatatableName(datatableDTOList, datatableNameFilter);
        LOGGER.info("After filter by name, the datatable list len = {}, now paging.", datatableDTOList.size());
        datatableDTOList = filterByDatasourceTypes(datatableDTOList, types);
        LOGGER.info("After filter by types, the datatable list len = {}, now paging.", datatableDTOList.size());
//        int startIndex = pageSize * (pageNumber - 1);
//        if (startIndex > datatableDTOList.size()) {
//            LOGGER.error("When find by node id, the page start index {} > datatableDtolist len {}", startIndex, datatableDTOList.size());
//            throw SecretpadException.of(SystemErrorCode.OUT_OF_RANGE_ERROR, "page start index > datatable list length.");
//        }
//        int endIndex = Math.min((startIndex + pageSize), datatableDTOList.size());
//        LOGGER.info("After page, we show from {} to {}", startIndex, endIndex);
        return DatatableListDTO.builder()
                .datatableDTOList(datatableDTOList)
                .totalDatatableNums(datatableDTOList.size())
                .build();
    }

    @Override
    public List<DatatableDTO> findAllDatatableByNodeId(String nodeId) {
        List<DatatableDTO> datatableDTOS = findByNodeId(nodeId, DATA_VENDOR_MANUAL);
        List<DatatableDTO> httpFeatureTableByNodeId = findHttpFeatureTableByNodeId(nodeId);
        httpFeatureTableByNodeId.addAll(datatableDTOS);
        return httpFeatureTableByNodeId;
    }

    private List<DatatableDTO> findHttpFeatureTableByNodeId(String nodeId) {
        List<DatatableDTO> featureList = new ArrayList<>();
        List<FeatureTableDO> featureTableDOList = featureTableRepository.findByNodeId(nodeId);
        if (!CollectionUtils.isEmpty(featureTableDOList)) {
            for (FeatureTableDO featureTableDO : featureTableDOList) {
                DatatableDTO datatableDTO = DatatableDTO.builder()
                        .datatableId(featureTableDO.getUpk().getFeatureTableId())
                        .datasourceId(DomainDatasourceConstants.DEFAULT_HTTP_DATASOURCE_ID)
                        .datatableName(featureTableDO.getFeatureTableName())
                        .nodeId(nodeId)
                        .datasourceType(DataSourceTypeEnum.HTTP.name())
                        .datasourceName(DomainDatasourceConstants.DEFAULT_HTTP_DATASOURCE_NAME)
                        .type(DataTableTypeEnum.HTTP.name())
                        .relativeUri(featureTableDO.getUrl())
                        .status(featureTableDO.getStatus())
                        .schema(featureTableDO.getColumns().stream().map(it ->
                                        new DatatableDTO.TableColumnDTO(it.getColName(), it.getColType(), it.getColComment()))
                                .collect(Collectors.toList()))
                        .build();
                featureList.add(datatableDTO);
            }
        }
        return featureList;
    }

    @Override
    public List<DatatableDTO> findByNodeId(String nodeId, @Nullable String vendor) {
        Domaindata.ListDomainDataRequestData.Builder builder = Domaindata.ListDomainDataRequestData.newBuilder()
                .setDomaindataType(DATA_TYPE_TABLE)
                .setDomainId(nodeId);
        if (vendor != null) {
            builder.setDomaindataVendor(vendor);
        }

        //protect unregistered domain
        if (!kusciaGrpcClientAdapter.isDomainRegistered(PlatformTypeEnum.CENTER.equals(PlatformTypeEnum.valueOf(plaformType)) ? localNodeId : nodeId)) {
            LOGGER.warn("domain not registered  nodeId: nodeId={}", nodeId);
            return new ArrayList<>();
        }
        Domaindata.ListDomainDataResponse responses = kusciaGrpcClientAdapter.listDomainData(
                Domaindata.ListDomainDataRequest.newBuilder()
                        .setData(builder.build()).build(), PlatformTypeEnum.CENTER.equals(PlatformTypeEnum.valueOf(plaformType)) ? localNodeId : nodeId);
        if (responses.getStatus().getCode() != 0) {
            LOGGER.error("lock up from kusciaapi failed: code={}, message={}, nodeId={}, vendor={}",
                    responses.getStatus().getCode(), responses.getStatus().getMessage(), nodeId, vendor);
            throw SecretpadException.of(DatatableErrorCode.QUERY_DATATABLE_FAILED);
        }
        return responses.getData().getDomaindataListList().stream().map(DatatableDTO::fromDomainData).collect(Collectors.toList());
    }

    @Override
    public void deleteDataTable(DatatableDTO.NodeDatatableId nodeDatatableId) {
        Domaindata.DeleteDomainDataRequest.Builder builder = Domaindata.DeleteDomainDataRequest.newBuilder()
                .setDomainId(nodeDatatableId.getNodeId())
                .setDomaindataId(nodeDatatableId.getDatatableId());
        Domaindata.DeleteDomainDataResponse response;
        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType))) {
            response = kusciaGrpcClientAdapter.deleteDomainData(builder.build(), nodeDatatableId.getNodeId());
        } else {
            response = kusciaGrpcClientAdapter.deleteDomainData(builder.build());
        }

        if (response.getStatus().getCode() != 0) {
            LOGGER.error("delete datatable failed: code={}, message={}, nodeId={}, datatableId={}",
                    response.getStatus().getCode(), response.getStatus().getMessage(), nodeDatatableId.getNodeId(), nodeDatatableId.getDatatableId());
            throw SecretpadException.of(DatatableErrorCode.DELETE_DATATABLE_FAILED);
        }
    }

    /**
     * Filter datatableDTO list by status
     *
     * @param datatableDTOList datatableDTO list
     * @param statusFilter     status filter
     * @return DatatableDTO list
     */
    private List<DatatableDTO> filterByStatus(List<DatatableDTO> datatableDTOList, String statusFilter) {
        if ((!STATUS_AVAILABLE.equalsIgnoreCase(statusFilter) && !STATUS_UNAVAILABLE.equalsIgnoreCase(statusFilter))) {
            return datatableDTOList;
        }
        return datatableDTOList.stream().filter(
                it -> statusFilter.equalsIgnoreCase(it.getStatus())
        ).collect(Collectors.toList());
    }

    /**
     * Filter datatableDTO list by datatable name
     *
     * @param datatableDTOList    datatableDTO list
     * @param datatableNameFilter datatable name filter
     * @return DatatableDTO list
     */
    private List<DatatableDTO> filterByDatatableName(List<DatatableDTO> datatableDTOList, String datatableNameFilter) {
        if (datatableNameFilter == null) {
            return datatableDTOList;
        }
        return datatableDTOList.stream().filter(
                it -> it.getDatatableName().contains(datatableNameFilter)
        ).collect(Collectors.toList());
    }


    private List<DatatableDTO> filterByDatasourceTypes(List<DatatableDTO> datatableDTOList, List<String> types) {
        if (CollectionUtils.isEmpty(types)) {
            return datatableDTOList;
        }
        return datatableDTOList.stream().filter(
                it -> types.contains(it.getDatasourceType())
        ).collect(Collectors.toList());
    }
}

