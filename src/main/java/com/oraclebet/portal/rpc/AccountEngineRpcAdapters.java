package com.oraclebet.portal.rpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclebet.accountengine.api.*;
import com.oraclebet.accountengine.api.dto.*;
import com.oraclebet.discovery.model.DiscoveryNodeType;
import com.oraclebet.discovery.nacos.rpc.NodeRpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * AccountEngine API 接口的 RPC 实现。
 * 通过 NodeRpcClient 调 AccountEngine HTTP 接口。
 */
@Configuration
public class AccountEngineRpcAdapters {

    @Bean
    public AccountEngineSettlementDataApi accountEngineSettlementDataApi(NodeRpcClient rpc, ObjectMapper mapper) {
        return new AccountEngineSettlementDataApi() {
            @Override
            public List<AccountEnginePositionLotDto> lockUnsettledOpenLots(String eventId, String marketId) {
                Object[] result = rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/settlement/lots/lock?eventId=" + eventId + "&marketId=" + marketId,
                        null, Object[].class);
                return result == null ? List.of() : Arrays.stream(result)
                        .map(o -> mapper.convertValue(o, AccountEnginePositionLotDto.class))
                        .toList();
            }

            @Override
            public void saveLots(List<AccountEnginePositionLotDto> lots) {
                rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/settlement/lots/save", lots, Void.class);
            }

            @Override
            public AccountEngineLedgerAccountStateDto findLedgerAccount(String userId, String currency, String accountType) {
                return rpc.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/settlement/ledger-account?userId=" + userId
                                + "&currency=" + currency + "&accountType=" + accountType,
                        AccountEngineLedgerAccountStateDto.class);
            }
        };
    }

    @Bean
    public AccountEngineSettleOrderApi accountEngineSettleOrderApi(NodeRpcClient rpc, ObjectMapper mapper) {
        return new AccountEngineSettleOrderApi() {
            @Override
            public Optional<AccountEngineSettleOrderDto> findById(String orderId) {
                try {
                    AccountEngineSettleOrderDto dto = rpc.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                            "/api/account/settle/orders/" + orderId,
                            AccountEngineSettleOrderDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<AccountEngineSettleOrderDto> findByIdForUpdate(String orderId) {
                try {
                    AccountEngineSettleOrderDto dto = rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                            "/api/account/settle/orders/" + orderId + "/for-update",
                            null, AccountEngineSettleOrderDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public boolean moveStatus(String orderId, String targetStatus) {
                Map result = rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/settle/orders/" + orderId + "/move-status?target=" + targetStatus,
                        null, Map.class);
                return result != null;
            }

            @Override
            public AccountEngineCancelOrderResultDto cancel(String orderId) {
                return rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/settle/orders/" + orderId + "/cancel",
                        null, AccountEngineCancelOrderResultDto.class);
            }
        };
    }

    @Bean
    public AccountEngineReservationQueryApi accountEngineReservationQueryApi(NodeRpcClient rpc) {
        return reservationId -> rpc.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                "/api/account/reservations/" + reservationId + "/state",
                String.class);
    }

    @Bean
    public AccountEngineUserApi accountEngineUserApi(NodeRpcClient rpc, ObjectMapper mapper) {
        return new AccountEngineUserApi() {
            @Override
            public AccountEngineUserDto signUp(AccountEngineSignUpCommand command) {
                return rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                        "/api/account/users/sign-up", command, AccountEngineUserDto.class);
            }

            @Override
            public Optional<AccountEngineUserDto> findByEmail(String email) {
                try {
                    AccountEngineUserDto dto = rpc.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                            "/api/account/users/by-email?email=" + email,
                            AccountEngineUserDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<AccountEngineUserDto> findByAccessToken(String accessToken) {
                try {
                    AccountEngineUserDto dto = rpc.get(DiscoveryNodeType.ACCOUNT_ENGINE,
                            "/api/account/users/by-access-token?accessToken=" + accessToken,
                            AccountEngineUserDto.class);
                    return Optional.ofNullable(dto);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
        };
    }

    @Bean
    public AccountEngineLedgerCommandApi accountEngineLedgerCommandApi(NodeRpcClient rpc) {
        return command -> rpc.post(DiscoveryNodeType.ACCOUNT_ENGINE,
                "/api/admin/ledger/apply", command, AccountEngineLedgerResultDto.class);
    }
}
