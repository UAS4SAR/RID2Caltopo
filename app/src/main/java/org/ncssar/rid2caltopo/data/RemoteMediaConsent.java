package org.ncssar.rid2caltopo.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request/resource-specific local grants; never persist or trust a server hint alone. */
public final class RemoteMediaConsent {
    public static final class Grant {
        public final String key;
        public final String resource;
        public final boolean automatic;
        private boolean valid;
        private final List<Runnable> cancellations = new ArrayList<>();

        private Grant(String key, String resource, boolean automatic, boolean valid) {
            this.key = key; this.resource = resource;
            this.automatic = automatic; this.valid = valid;
        }
        public synchronized boolean isAllowed() { return valid; }
        public void onRevocation(Runnable action) {
            synchronized (this) {
                if (valid) { cancellations.add(action); return; }
            }
            action.run();
        }
        private synchronized List<Runnable> invalidate() {
            if (!valid) return new ArrayList<>();
            valid = false;
            List<Runnable> result = new ArrayList<>(cancellations);
            cancellations.clear();
            return result;
        }
    }

    private boolean enabled;
    private final Map<String, Grant> grants = new LinkedHashMap<>();

    public void update(boolean enabled) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            this.enabled = enabled;
            if (!enabled) for (Grant grant : grants.values())
                if (grant.automatic) actions.addAll(grant.invalidate());
        }
        actions.forEach(Runnable::run);
    }
    public synchronized Grant receive(String key, String resource, boolean serverRequiresConsent) {
        Grant old = grants.get(key);
        if (old != null && old.resource.equals(resource)) return old;
        if (old != null || key.isEmpty() || resource.isEmpty() || grants.size() >= 2048)
            return new Grant(key, resource, true, false);
        boolean automatic = !serverRequiresConsent && enabled;
        Grant grant = new Grant(key, resource, automatic, automatic);
        grants.put(key, grant);
        return grant;
    }
    public synchronized Grant find(String key, String resource) {
        Grant grant = grants.get(key);
        return grant != null && grant.resource.equals(resource) ? grant : null;
    }
    /** Only a local UI approval may call this, never a server acknowledgement. */
    public Grant approve(String key) {
        List<Runnable> actions;
        Grant result;
        synchronized (this) {
            Grant old = grants.get(key);
            if (old == null) return null;
            actions = old.invalidate();
            result = new Grant(key, old.resource, false, true);
            grants.put(key, result);
        }
        actions.forEach(Runnable::run);
        return result;
    }
    public void cancel(String key) {
        List<Runnable> actions;
        synchronized (this) {
            Grant grant = grants.get(key);
            actions = grant == null ? new ArrayList<>() : grant.invalidate();
        }
        actions.forEach(Runnable::run);
    }
    public synchronized void finish(String key, Grant grant) {
        if (grants.get(key) == grant) grant.invalidate();
    }
    void clearForTesting() { reset(); synchronized (this) { grants.clear(); } }
    public void reset() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            for (Grant grant : grants.values()) actions.addAll(grant.invalidate());
            // Tombstones prevent reconnects/replayed IDs from reviving grants.
        }
        actions.forEach(Runnable::run);
    }
}
