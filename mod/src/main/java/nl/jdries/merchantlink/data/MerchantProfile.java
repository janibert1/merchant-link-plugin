package nl.jdries.merchantlink.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MerchantProfile {
    public final UUID ownerId;
    public UUID merchantUuid;
    public String merchantWorldKey;
    public double merchantX;
    public double merchantY;
    public double merchantZ;

    public final List<LinkedChest> supplyChests = new ArrayList<>();
    public LinkedChest returnChest;
    public final Map<String, Listing> listings = new LinkedHashMap<>();

    public MerchantProfile(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public boolean hasMerchantHome() {
        return merchantWorldKey != null;
    }

    public MerchantProfile copy() {
        MerchantProfile copy = new MerchantProfile(ownerId);
        copy.merchantUuid = merchantUuid;
        copy.merchantWorldKey = merchantWorldKey;
        copy.merchantX = merchantX;
        copy.merchantY = merchantY;
        copy.merchantZ = merchantZ;
        copy.supplyChests.addAll(supplyChests);
        copy.returnChest = returnChest;
        for (Map.Entry<String, Listing> entry : listings.entrySet()) {
            copy.listings.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }
}
