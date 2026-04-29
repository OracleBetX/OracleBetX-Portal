package com.oraclebet.portal.catalog;

import com.oraclebet.catalog.api.ProductCatalogApi;
import com.oraclebet.catalog.dto.InstrumentDto;
import com.oraclebet.catalog.dto.MarketDto;
import com.oraclebet.catalog.dto.ProductRootDto;
import com.oraclebet.matchengine.marketdata.entity.InstrumentEntity;
import com.oraclebet.matchengine.marketdata.entity.MarketEntity;
import com.oraclebet.matchengine.marketdata.entity.ProductRootEntity;
import com.oraclebet.matchengine.marketdata.repository.InstrumentCatalogRepository;
import com.oraclebet.matchengine.marketdata.repository.MarketCatalogRepository;
import com.oraclebet.matchengine.marketdata.repository.ProductRootRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * ProductCatalogApi 的 MongoDB 实现。
 * Portal 直接连 MongoDB 查产品目录，不走 MatchEngine RPC。
 */
@Component
public class ProductCatalogApiAdapter implements ProductCatalogApi {

    private final ProductRootRepository productRootRepository;
    private final MarketCatalogRepository marketCatalogRepository;
    private final InstrumentCatalogRepository instrumentCatalogRepository;

    public ProductCatalogApiAdapter(ProductRootRepository productRootRepository,
                                     MarketCatalogRepository marketCatalogRepository,
                                     InstrumentCatalogRepository instrumentCatalogRepository) {
        this.productRootRepository = productRootRepository;
        this.marketCatalogRepository = marketCatalogRepository;
        this.instrumentCatalogRepository = instrumentCatalogRepository;
    }

    @Override
    public List<ProductRootDto> findTradableProducts(int limit) {
        return productRootRepository.findTradableVisible(limit).stream().map(this::toDto).toList();
    }

    @Override
    public Optional<ProductRootDto> findProductRootById(String id) {
        return Optional.ofNullable(productRootRepository.findById(id)).map(this::toDto);
    }

    @Override
    public Optional<ProductRootDto> findByLegacyEventId(String eventId) {
        return Optional.ofNullable(productRootRepository.findByEventId(eventId)).map(this::toDto);
    }

    @Override
    public void updateProductRootStatus(String id, String status) {
        ProductRootEntity entity = productRootRepository.findById(id);
        if (entity != null) {
            entity.setStatus(status);
            productRootRepository.saveAll(List.of(entity));
        }
    }

    @Override
    public void updateMarketStatusByLegacyId(String eventId, String marketId, String status) {
        MarketEntity entity = marketCatalogRepository.findByLegacyEventIdAndMarketId(eventId, marketId);
        if (entity != null) {
            entity.setStatus(status);
            entity.setTradable(false);
            marketCatalogRepository.save(entity);
        }
    }

    @Override
    public List<MarketDto> findMarketsByProductRootId(String productRootId) {
        return marketCatalogRepository.findByProductRootId(productRootId).stream().map(this::toDto).toList();
    }

    @Override
    public List<MarketDto> findTradableMarketsByProductRootId(String productRootId) {
        return marketCatalogRepository.findTradableByProductRootId(productRootId).stream().map(this::toDto).toList();
    }

    @Override
    public List<InstrumentDto> findInstrumentsByMarketId(String marketId) {
        return instrumentCatalogRepository.findByMarketId(marketId).stream().map(this::toDto).toList();
    }

    @Override
    public List<InstrumentDto> findTradableInstrumentsByMarketId(String marketId) {
        return instrumentCatalogRepository.findTradableByMarketId(marketId).stream().map(this::toDto).toList();
    }

    @Override
    public Optional<InstrumentDto> findInstrumentByCode(String instrumentCode) {
        return Optional.empty(); // TODO
    }

    @Override
    public Optional<ProductRootDto> findProductTreeById(String productRootId) {
        ProductRootEntity root = productRootRepository.findById(productRootId);
        if (root == null) return Optional.empty();
        ProductRootDto dto = toDto(root);
        List<MarketDto> markets = findTradableMarketsByProductRootId(productRootId);
        for (MarketDto m : markets) {
            m.setInstruments(findTradableInstrumentsByMarketId(m.getId()));
        }
        dto.setMarkets(markets);
        return Optional.of(dto);
    }

    @Override
    public List<ProductRootDto> findTradableProductTrees(int limit) {
        List<ProductRootDto> roots = findTradableProducts(limit);
        for (ProductRootDto root : roots) {
            List<MarketDto> markets = findTradableMarketsByProductRootId(root.getId());
            for (MarketDto m : markets) {
                m.setInstruments(findTradableInstrumentsByMarketId(m.getId()));
            }
            root.setMarkets(markets);
        }
        return roots;
    }

    // -------------------------------------------------------
    // Entity → DTO
    // -------------------------------------------------------

    private ProductRootDto toDto(ProductRootEntity e) {
        ProductRootDto d = new ProductRootDto();
        d.setId(e.getId());
        d.setProductRootKey(e.getProductRootKey());
        d.setRootType(e.getRootType());
        d.setSourceSystem(e.getSourceSystem());
        d.setDisplayName(e.getDisplayName());
        d.setProductType(e.getProductType());
        d.setLegacyProductId(e.getLegacyProductId());
        d.setLegacyEventId(e.getLegacyEventId());
        d.setStatus(e.getStatus());
        d.setTradable(e.getTradable());
        d.setVisible(e.getVisible());
        d.setExt(e.getExt());
        return d;
    }

    private MarketDto toDto(MarketEntity e) {
        MarketDto d = new MarketDto();
        d.setId(e.getId());
        d.setMarketKey(e.getMarketKey());
        d.setProductRootId(e.getProductRootId());
        d.setMarketType(e.getMarketType());
        d.setDisplayName(e.getDisplayName());
        d.setLegacyEventId(e.getLegacyEventId());
        d.setLegacyMarketId(e.getLegacyMarketId());
        d.setLegacyProductId(e.getLegacyProductId());
        d.setStatus(e.getStatus());
        d.setTradable(e.getTradable());
        d.setExt(e.getExt());
        return d;
    }

    private InstrumentDto toDto(InstrumentEntity e) {
        InstrumentDto d = new InstrumentDto();
        d.setId(e.getId());
        d.setInstrumentKey(e.getInstrumentKey());
        d.setMarketId(e.getMarketId());
        d.setProductRootId(e.getProductRootId());
        d.setInstrumentCode(e.getInstrumentCode());
        d.setInstrumentType(e.getInstrumentType());
        d.setDisplayName(e.getDisplayName());
        d.setLegacyProductId(e.getLegacyProductId());
        d.setLegacyEventId(e.getLegacyEventId());
        d.setLegacyMarketId(e.getLegacyMarketId());
        d.setLegacySelectionId(e.getLegacySelectionId());
        d.setStatus(e.getStatus());
        d.setTradable(e.getTradable());
        d.setExt(e.getExt());
        return d;
    }
}
