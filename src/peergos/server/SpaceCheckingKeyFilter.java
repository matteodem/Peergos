package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

/** This class checks whether a given user is using more storage space than their quota
 *
 */
public class SpaceCheckingKeyFilter {

    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final long defaultQuota;

    private final Map<PublicKeyHash, Stat> currentView = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Usage> usage = new ConcurrentHashMap<>();

    private static class Stat {
        public final String owner;
        private MaybeMultihash target;
        private long directRetainedStorage;
        private Set<PublicKeyHash> ownedKeys;

        public Stat(String owner, MaybeMultihash target, long directRetainedStorage, Set<PublicKeyHash> ownedKeys) {
            this.owner = owner;
            this.target = target;
            this.directRetainedStorage = directRetainedStorage;
            this.ownedKeys = ownedKeys;
        }

        public synchronized void update(MaybeMultihash target, Set<PublicKeyHash> ownedKeys, long retainedStorage) {
            this.target = target;
            this.ownedKeys = Collections.unmodifiableSet(ownedKeys);
            this.directRetainedStorage = retainedStorage;
        }

        public synchronized long getDirectRetainedStorage() {
            return directRetainedStorage;
        }

        public synchronized MaybeMultihash getRoot() {
            return target;
        }

        public synchronized Set<PublicKeyHash> getOwnedKeys() {
            return Collections.unmodifiableSet(ownedKeys);
        }
    }

    private static class Usage {
        private long usage, quota;
        private Map<PublicKeyHash, Long> pending = new HashMap<>();

        public Usage(long usage, long quota) {
            this.usage = usage;
            this.quota = quota;
        }

        protected synchronized void confirmUsage(PublicKeyHash writer, long usageDelta) {
            pending.remove(writer);
            usage += usageDelta;
        }

        protected synchronized void addPending(PublicKeyHash writer, long usageDelta) {
            pending.put(writer, pending.getOrDefault(writer, 0L) + usageDelta);
        }

        protected synchronized void clearPending(PublicKeyHash writer) {
            pending.remove(writer);
        }

        protected synchronized long getPending(PublicKeyHash writer) {
            return pending.getOrDefault(writer, 0L);
        }

        protected synchronized void setQuota(long newQuota) {
            quota = newQuota;
        }

        protected synchronized long remainingQuota() {
            return quota - usage - pending.values().stream().mapToLong(x -> x).sum();
        }
    }

    /**
     *
     * @param core
     * @param mutable
     * @param dht
     * @param defaultQuota The quota for users who aren't explicitly listed in the whitelist
     */
    public SpaceCheckingKeyFilter(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht, long defaultQuota) {
        System.out.println("Using default user space quota of " + defaultQuota);
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.defaultQuota = defaultQuota;
        // It's okay to do this asynchronously, as any users that try to write will get an error until their usage has
        // been loaded
        new Thread(this::loadAllOwners).start();
    }

    private void loadAllOwners() {
        try {
            List<String> usernames = core.getUsernames("").get();
            for (String username : usernames) {
                Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
                publicKeyHash.ifPresent(keyHash -> processCorenodeEvent(username, keyHash));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void accept(CorenodeEvent event) {
        currentView.computeIfAbsent(event.keyHash, k -> new Stat(event.username, MaybeMultihash.empty(), 0, Collections.emptySet()));
        usage.putIfAbsent(event.username, new Usage(0, defaultQuota));
        ForkJoinPool.commonPool().submit(() -> processCorenodeEvent(event.username, event.keyHash));
    }

    /** Update our view of the world because a user has changed their public key (or registered)
     *
     * @param username
     * @param ownedKeyHash
     */
    public void processCorenodeEvent(String username, PublicKeyHash ownedKeyHash) {
        try {
            usage.putIfAbsent(username, new Usage(0, defaultQuota));
            Set<PublicKeyHash> childrenKeys = WriterData.getDirectOwnedKeys(ownedKeyHash, mutable, dht);
            currentView.computeIfAbsent(ownedKeyHash, k -> new Stat(username, MaybeMultihash.empty(), 0, childrenKeys));
            Stat current = currentView.get(ownedKeyHash);
            MaybeMultihash updatedRoot = mutable.getPointerTarget(ownedKeyHash, dht).get();
            processMutablePointerEvent(ownedKeyHash, current.target, updatedRoot);
            for (PublicKeyHash childKey : childrenKeys) {
                processCorenodeEvent(username, childKey);
            }
        } catch (Throwable e) {
            System.err.println("Error loading storage for user: " + username + ", disabling their quota");
            usage.get(username).setQuota(0);
            e.printStackTrace();
        }
    }

    public void accept(MutableEvent event) {
        try {
            HashCasPair hashCasPair = dht.getSigningKey(event.writer)
                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                            .unsignMessage(event.writerSignedBtreeRootHash)))).get();
            processMutablePointerEvent(event.writer, hashCasPair.original, hashCasPair.updated);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void processMutablePointerEvent(PublicKeyHash writer, MaybeMultihash existingRoot, MaybeMultihash newRoot) {
        if (existingRoot.equals(newRoot))
            return;
        Stat current = currentView.get(writer);
        if (current == null)
            throw new IllegalStateException("Unknown writer key hash: " + writer);
        if (! newRoot.isPresent()) {
            current.update(MaybeMultihash.empty(), Collections.emptySet(), 0);
            if (existingRoot.isPresent()) {
                try {
                    // subtract data size from orphaned child keys (this assumes the keys form a tree without dups)
                    Set<PublicKeyHash> updatedOwned = WriterData.getWriterData(writer, newRoot, dht).get().props.ownedKeys;
                    processRemovedOwnedKeys(updatedOwned);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return;
        }

        try {
            synchronized (current) {
                long changeInStorage = dht.getChangeInContainedSize(current.target, newRoot.get()).get();
                Set<PublicKeyHash> updatedOwned = WriterData.getWriterData(writer, newRoot, dht).get().props.ownedKeys;
                for (PublicKeyHash owned : updatedOwned) {
                    currentView.computeIfAbsent(owned, k -> new Stat(current.owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
                }
                Usage currentUsage = usage.get(current.owner);
                currentUsage.confirmUsage(writer, changeInStorage);

                HashSet<PublicKeyHash> removedChildren = new HashSet<>(current.getOwnedKeys());
                removedChildren.removeAll(updatedOwned);
                processRemovedOwnedKeys(removedChildren);
                current.update(newRoot, updatedOwned, current.directRetainedStorage + changeInStorage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processRemovedOwnedKeys(Set<PublicKeyHash> removed) {
        for (PublicKeyHash ownedKey : removed) {
            try {
                MaybeMultihash currentTarget = mutable.getPointerTarget(ownedKey, dht).get();
                processMutablePointerEvent(ownedKey, currentTarget, MaybeMultihash.empty());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean allowWrite(PublicKeyHash writer, int size) {
        Stat state = currentView.get(writer);
        if (state == null)
            throw new IllegalStateException("Unknown writing key hash: " + writer);

        Usage usage = this.usage.get(state.owner);
        if (usage.remainingQuota() - size <= 0) {
            long pending = usage.getPending(writer);
            usage.clearPending(writer);
            throw new IllegalStateException("Storage quota reached! Used "
                    + usage.usage + " out of " + usage.quota + " bytes. Rejecting write of size " + (size + pending) + ". Please delete some files.");
        }
        usage.addPending(writer, size);
        return true;
    }
}
