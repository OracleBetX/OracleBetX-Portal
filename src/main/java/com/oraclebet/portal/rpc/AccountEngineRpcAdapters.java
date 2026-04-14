package com.oraclebet.portal.rpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.accountengine.api.*;
import com.oraclebet.accountengine.api.dto.*;
import com.oraclebet.web.model.PagedList;
import com.oraclebet.discovery.nacos.rpc.GatewayAddressProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * AccountEngine API 接口的实现。
 * 通过 GatewayAddressProvider 走网关转发到 AccountEngine。
 */
@Configuration
public class AccountEngineRpcAdapters {

    @Bean
    public AccountEngineSettlementDataApi accountEngineSettlementDataApi(GatewayAddressProvider gw,
                                                                         RestTemplate nodeRpcRestTemplate,
                                                                         ObjectMapper mapper) {
        return new AccountEngineSettlementDataApi() {
            @Override
            public List<AccountEnginePositionLotDto> lockUnsettledOpenLots(String eventId, String marketId) {
                String url = gw.getGatewayUrl() + "/api/account/settlement/lots/lock?eventId=" + eventId + "&marketId=" + marketId;
                Object[] result = nodeRpcRestTemplate.postForObject(url, null, Object[].class);
                return result == null ? List.of() : Arrays.stream(result)
                        .map(o -> mapper.convertValue(o, AccountEnginePositionLotDto.class))
                        .toList();
            }

            @Override
            public void saveLots(List<AccountEnginePositionLotDto> lots) {
                String url = gw.getGatewayUrl() + "/api/account/settlement/lots/save";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(lots, headers), Void.class);
            }

            @Override
            public AccountEngineLedgerAccountStateDto findLedgerAccount(String userId, String currency, String accountType) {
                String url = gw.getGatewayUrl() + "/api/account/settlement/ledger-account?userId=" + userId
                        + "&currency=" + currency + "&accountType=" + accountType;
                return nodeRpcRestTemplate.getForObject(url, AccountEngineLedgerAccountStateDto.class);
            }
        };
    }

    @Bean
    public AccountEngineSettleOrderApi accountEngineSettleOrderApi(GatewayAddressProvider gw,
                                                                    RestTemplate nodeRpcRestTemplate,
                                                                    ObjectMapper mapper) {
        return new AccountEngineSettleOrderApi() {
            @Override
            public Optional<AccountEngineSettleOrderDto> findById(String orderId) {
                try {
                    String url = gw.getGatewayUrl() + "/api/account/settle/orders/" + orderId;
                    AccountEngineSettleOrderDto dto = nodeRpcRestTemplate.getForObject(url, AccountEngineSettleOrderDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<AccountEngineSettleOrderDto> findByIdForUpdate(String orderId) {
                try {
                    String url = gw.getGatewayUrl() + "/api/account/settle/orders/" + orderId + "/for-update";
                    AccountEngineSettleOrderDto dto = nodeRpcRestTemplate.postForObject(url, null, AccountEngineSettleOrderDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public boolean moveStatus(String orderId, String targetStatus) {
                String url = gw.getGatewayUrl() + "/api/account/settle/orders/" + orderId + "/move-status?target=" + targetStatus;
                Map result = nodeRpcRestTemplate.postForObject(url, null, Map.class);
                return result != null;
            }

            @Override
            public AccountEngineCancelOrderResultDto cancel(String orderId) {
                String url = gw.getGatewayUrl() + "/api/account/settle/orders/" + orderId + "/cancel";
                return nodeRpcRestTemplate.postForObject(url, null, AccountEngineCancelOrderResultDto.class);
            }
        };
    }

    @Bean
    public AccountEngineReservationQueryApi accountEngineReservationQueryApi(GatewayAddressProvider gw,
                                                                             RestTemplate nodeRpcRestTemplate) {
        return reservationId -> nodeRpcRestTemplate.getForObject(
                gw.getGatewayUrl() + "/api/account/reservations/" + reservationId + "/state",
                String.class);
    }

    @Bean
    public AccountEngineUserApi accountEngineUserApi(GatewayAddressProvider gw,
                                                     RestTemplate nodeRpcRestTemplate,
                                                     ObjectMapper mapper) {
        return new AccountEngineUserApi() {
            @Override
            public AccountEngineUserDto signUp(AccountEngineSignUpCommand command) {
                String url = gw.getGatewayUrl() + "/api/account/users/sign-up";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                return nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(command, headers), AccountEngineUserDto.class).getBody();
            }

            @Override
            public Optional<AccountEngineUserDto> findByEmail(String email) {
                try {
                    String url = gw.getGatewayUrl() + "/api/account/users/by-email?email=" + email;
                    return Optional.ofNullable(nodeRpcRestTemplate.getForObject(url, AccountEngineUserDto.class));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<AccountEngineUserDto> findByAccessToken(String accessToken) {
                try {
                    String url = gw.getGatewayUrl() + "/api/account/users/by-access-token?accessToken=" + accessToken;
                    return Optional.ofNullable(nodeRpcRestTemplate.getForObject(url, AccountEngineUserDto.class));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, String> findUserIdsByEmails(List<String> emails) {
                try {
                    String url = gw.getGatewayUrl() + "/api/account/users/by-emails";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map result = nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(emails, headers), Map.class).getBody();
                    return result != null ? result : Map.of();
                } catch (Exception e) {
                    return Map.of();
                }
            }
        };
    }

    @Bean
    public AccountEngineLpPositionApi accountEngineLpPositionApi(GatewayAddressProvider gw,
                                                                  RestTemplate nodeRpcRestTemplate) {
        return new AccountEngineLpPositionApi() {
            @Override
            public void upsertInitEventAccount(AccountEngineLpEventAccountInitCommand command) {
                String url = gw.getGatewayUrl() + "/api/account/lp/event-account";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(command, headers), Void.class);
            }

            @Override
            public void saveInitLot(AccountEngineLpLotInitCommand command) {
                String url = gw.getGatewayUrl() + "/api/account/lp/lot";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(command, headers), Void.class);
            }

            @Override
            public void syncPositionToRedis(String userId, String eventId, String marketId, String selectionId) {
                String url = gw.getGatewayUrl() + "/api/account/lp/sync-redis?userId=" + userId + "&eventId=" + eventId
                        + "&marketId=" + marketId + "&selectionId=" + selectionId;
                nodeRpcRestTemplate.postForObject(url, null, Void.class);
            }
        };
    }

    @Bean
    public AccountEngineLedgerCommandApi accountEngineLedgerCommandApi(GatewayAddressProvider gw,
                                                                       RestTemplate nodeRpcRestTemplate) {
        return command -> {
            String url = gw.getGatewayUrl() + "/api/admin/ledger/apply";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return nodeRpcRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(command, headers), AccountEngineLedgerResultDto.class).getBody();
        };
    }

    @Bean
    public AccountEngineOrderQueryApi accountEngineOrderQueryApi(GatewayAddressProvider gw,
                                                                  RestTemplate nodeRpcRestTemplate,
                                                                  ObjectMapper mapper) {
        return (userId, eventId, marketId, selectionId, status, page, pageSize) -> {
            StringBuilder sb = new StringBuilder(gw.getGatewayUrl()).append("/api/account/orders?");
            if (userId != null && !userId.isBlank()) sb.append("userId=").append(userId).append("&");
            if (eventId != null && !eventId.isBlank()) sb.append("eventId=").append(eventId).append("&");
            if (marketId != null && !marketId.isBlank()) sb.append("marketId=").append(marketId).append("&");
            if (selectionId != null && !selectionId.isBlank()) sb.append("selectionId=").append(selectionId).append("&");
            if (status != null && !status.isBlank()) sb.append("status=").append(status).append("&");
            if (page != null) sb.append("page=").append(page).append("&");
            if (pageSize != null) sb.append("pageSize=").append(pageSize).append("&");
            String url = sb.toString().replaceAll("&$", "");
            Map result = nodeRpcRestTemplate.getForObject(url, Map.class);
            if (result == null) return new PagedList<>(List.of(), 0);
            List<OrderDto> items = ((List<?>) result.getOrDefault("items", List.of())).stream()
                    .map(o -> mapper.convertValue(o, OrderDto.class))
                    .toList();
            int count = result.containsKey("count") ? ((Number) result.get("count")).intValue() : items.size();
            return new PagedList<>(items, count);
        };
    }
}
