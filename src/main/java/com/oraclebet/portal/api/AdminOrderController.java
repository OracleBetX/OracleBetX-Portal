package com.oraclebet.portal.api;

import com.oraclebet.accountengine.api.AccountEngineOrderQueryApi;
import com.oraclebet.accountengine.api.dto.OrderDto;
import com.oraclebet.web.model.PagedList;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台 - 订单查询（无需授权）
 * 通过 RPC 调用 AccountEngine 订单接口
 */
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AccountEngineOrderQueryApi orderQueryApi;

    @GetMapping
    public Map<String, Object> listOrders(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String marketId,
            @RequestParam(required = false) String selectionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {

        PagedList<OrderDto> result = orderQueryApi.queryOrders(
                userId, eventId, marketId, selectionId, status, page, pageSize
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pagination", Map.of(
                "page", page,
                "size", pageSize,
                "total", result.getCount(),
                "totalPages", result.getCount() == 0 ? 0 : (result.getCount() + pageSize - 1) / pageSize
        ));
        out.put("items", result.getItems());
        return out;
    }
}
