/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.misc.Utils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Represents a set of ATN configurations (see {@link ATNConfig}). As
 * configurations are added to the set, they are merged with other
 * {@link ATNConfig} instances already in the set when possible using the
 * graph-structured stack.
 *
 * <p>An instance of this class represents the complete set of positions (with
 * context) in an ATN which would be associated with a single DFA state. Its
 * internal representation is more complex than traditional state used for NFA
 * to DFA conversion due to performance requirements (both improving speed and
 * reducing memory overhead) as well as supporting features such as semantic
 * predicates and non-greedy operators in a form to support ANTLR's prediction
 * algorithm.</p>
 *
 * <p>Writable sets use a primitive {@code long}-keyed map for the
 * {@code (state, alt)} merge index so hot add/merge paths avoid
 * {@link Long} boxing on every lookup.</p>
 *
 * @author Sam Harwell
 */
public class ATNConfigSet implements Set<ATNConfig> {

	/**
	 * Minimum expected-element hint passed to the primitive {@code long}-keyed
	 * merge map when a positive capacity is requested. Matches the previous
	 * {@code HashMap} default capacity floor so small hints still expand
	 * reasonably during closure fan-out.
	 */
	private static final int MIN_MERGED_CONFIG_CAPACITY = 16;

	/**
	 * Capacity floor shared by hot-path scratch sets (lexer reach, parser
	 * reach/intermediate, epsilon-closure BFS buffers).
	 */
	static final int SCRATCH_CAPACITY_FLOOR = 16;

	/**
	 * When the number of configs is less than table-length / this factor,
	 * {@link #clear()} removes known merge keys one-by-one (O(n)) instead of
	 * bulk-filling the open-addressed table (O(capacity)). Retained scratch
	 * sets often keep a large table after a wide fan-out edge while later
	 * edges hold only a handful of configs.
	 */
	static final int SPARSE_CLEAR_CAPACITY_FACTOR = 4;

	/**
	 * Converts a source config count into a scratch-buffer capacity hint used
	 * when allocating or sizing retained {@link ATNConfigSet} instances on
	 * prediction / lexing hot paths. Doubles the source size (with overflow
	 * guard) and floors at {@link #SCRATCH_CAPACITY_FLOOR}.
	 *
	 * @param sourceSize number of configs in the source set, or {@code 0}
	 * @return positive expected-element capacity for a writable config set
	 */
	static int scratchCapacity(int sourceSize) {
		if (sourceSize <= 0) {
			return SCRATCH_CAPACITY_FLOOR;
		}
		if (sourceSize < (Integer.MAX_VALUE >> 1)) {
			return Math.max(SCRATCH_CAPACITY_FLOOR, sourceSize << 1);
		}
		return Integer.MAX_VALUE;
	}

	/**
	 * This maps (state, alt) -> merged {@link ATNConfig}. The key does not account for
	 * the {@link ATNConfig#getSemanticContext} of the value, which is only a problem if a single
	 * {@code ATNConfigSet} contains two configs with the same state and alternative
	 * but different semantic contexts. When this case arises, the first config
	 * added to this map stays, and the remaining configs are placed in {@link #unmerged}.
	 * <p>
	 * This map is only used for optimizing the process of adding configs to the set,
	 * and is {@code null} for read-only sets stored in the DFA.
	 * <p>
	 * Implemented as a private primitive {@code long}-keyed open-addressed map
	 * (package-private storage; not part of any public or protected API) so
	 * {@link #getKey} values are stored and looked up without {@link Long}
	 * boxing on the prediction hot path. Hot {@link #add} / {@link #contains}
	 * hash each key once per operation. {@link #clear()} uses an empty-fast
	 * path and may sparse-remove known keys when occupancy is low relative to
	 * table capacity (see {@link #SPARSE_CLEAR_CAPACITY_FACTOR}). External
	 * callers interact only with the {@link Set}{@code <ATNConfig>} surface
	 * of this class.
	 */
	private final ClearableLongObjectHashMap<ATNConfig> mergedConfigs;
	/**
	 * This is an "overflow" list holding configs which cannot be merged with one
	 * of the configs in {@link #mergedConfigs} but have a colliding key. This
	 * occurs when two configs in the set have the same state and alternative but
	 * different semantic contexts.
	 * <p>
	 * This list is only used for optimizing the process of adding configs to the set,
	 * and is {@code null} for read-only sets stored in the DFA.
	 */
	private final ArrayList<ATNConfig> unmerged;
	/**
	 * This is a list of all configs in this set.
	 */
	private final ArrayList<ATNConfig> configs;

	private int uniqueAlt;
	private ConflictInfo conflictInfo;
	// Used in parser and lexer. In lexer, it indicates we hit a pred
	// while computing a closure operation.  Don't make a DFA state from this.
	private boolean hasSemanticContext;
	private boolean dipsIntoOuterContext;
	/**
	 * When {@code true}, this config set represents configurations where the entire
	 * outer context has been consumed by the ATN interpreter. This prevents the
	 * {@link ParserATNSimulator#closure} from pursuing the global FOLLOW when a
	 * rule stop state is reached with an empty prediction context.
	 * <p>
	 * Note: {@code outermostConfigSet} and {@link #dipsIntoOuterContext} should never
	 * be true at the same time.
	 */
	private boolean outermostConfigSet;

	/**
	 * Cached {@link #hashCode()} for both writable scratch sets and readonly
	 * DFA sets. {@code 0} means uncomputed (zero is reserved; a raw hash of
	 * zero is stored as {@code 1}). Invalidated on every mutating operation
	 * that participates in {@link #hashCode()}.
	 */
	private int cachedHashCode;

	public ATNConfigSet() {
		this(0);
	}

	/**
	 * Constructs an empty, writable config set with the given capacity hint for
	 * the primary configuration storage. Larger hints reduce rehashing when the
	 * approximate number of configurations is known in advance (for example
	 * when seeding a set from another configuration set during closure or
	 * reach operations).
	 *
	 * <p>
	 * The hint is treated as an <em>expected element count</em> for both the
	 * configuration list and the primitive {@code (state, alt)} merge map. The
	 * merge-map expected size is floored at {@link #MIN_MERGED_CONFIG_CAPACITY}
	 * so that small expected sizes which later expand during closure do not
	 * rehash more aggressively than an unhinted set.</p>
	 *
	 * @param expectedSize expected number of configurations, or {@code <= 0}
	 * for the default map/list capacities
	 */
	public ATNConfigSet(int expectedSize) {
		if (expectedSize > 0) {
			this.mergedConfigs = new ClearableLongObjectHashMap<>(mergedMapExpectedElements(expectedSize));
			this.unmerged = new ArrayList<>();
			this.configs = new ArrayList<>(expectedSize);
		}
		else {
			this.mergedConfigs = new ClearableLongObjectHashMap<>();
			this.unmerged = new ArrayList<>();
			this.configs = new ArrayList<>();
		}

		this.uniqueAlt = ATN.INVALID_ALT_NUMBER;
	}

	/**
	 * Converts an expected configuration count into an expected-element hint for
	 * the private primitive merge map. The result is never smaller than
	 * {@link #MIN_MERGED_CONFIG_CAPACITY} so undersized hints cannot regress
	 * expansion cost relative to an unhinted map when the set grows beyond the
	 * estimate.
	 */
	private static int mergedMapExpectedElements(int expectedSize) {
		if (expectedSize < MIN_MERGED_CONFIG_CAPACITY) {
			return MIN_MERGED_CONFIG_CAPACITY;
		}
		return expectedSize;
	}

	@SuppressWarnings("unchecked")
	protected ATNConfigSet(ATNConfigSet set, boolean readonly) {
		if (readonly) {
			this.mergedConfigs = null;
			this.unmerged = null;
			// Single exact-sized array copy instead of clone() + trimToSize().
			this.configs = new ArrayList<>(set.configs);
		} else if (!set.isReadOnly()) {
			// Object.clone preserves ClearableLongObjectHashMap (package-private
			// HPPC subclass); HPPC never appears on public/protected signatures.
			this.mergedConfigs = (ClearableLongObjectHashMap<ATNConfig>)set.mergedConfigs.clone();
			this.unmerged = (ArrayList<ATNConfig>)set.unmerged.clone();
			this.configs = (ArrayList<ATNConfig>)set.configs.clone();
		} else {
			// Writable clone of a read-only set: clone(false) populates configs
			// and mergedConfigs via addAll(), so start with an empty list to avoid
			// duplicating every element.
			this.mergedConfigs = new ClearableLongObjectHashMap<>(mergedMapExpectedElements(set.configs.size()));
			this.unmerged = new ArrayList<>();
			this.configs = new ArrayList<>(set.configs.size());
		}

		this.dipsIntoOuterContext = set.dipsIntoOuterContext;
		this.hasSemanticContext = set.hasSemanticContext;
		this.outermostConfigSet = set.outermostConfigSet;

		if (readonly || !set.isReadOnly()) {
			this.uniqueAlt = set.uniqueAlt;
			this.conflictInfo = set.conflictInfo;
		}

		if (readonly) {
			// Preserve an already-computed hash lazily: when enable_global_context_dfa
			// is false, transient full-context states are never hashed into dfa.states.
			this.cachedHashCode = set.cachedHashCode;
		}

		// if (!readonly && set.isReadOnly()) -> addAll is called from clone()
	}

	/**
	 * Get the set of all alternatives represented by configurations in this
	 * set.
	 */
	@NotNull
	public BitSet getRepresentedAlternatives() {
		if (conflictInfo != null) {
			return (BitSet)conflictInfo.getConflictedAlts().clone();
		}

		BitSet alts = new BitSet();
		for (ATNConfig config : this) {
			alts.set(config.getAlt());
		}

		return alts;
	}

	public final boolean isReadOnly() {
		return mergedConfigs == null;
	}

	/**
	 * Package-private check for {@link ConflictDetector}: whether this writable
	 * set holds any key-colliding configs in {@link #unmerged}.
	 */
	final boolean hasUnmerged() {
		return unmerged != null && !unmerged.isEmpty();
	}

	public boolean isOutermostConfigSet() {
		return outermostConfigSet;
	}

	public void setOutermostConfigSet(boolean outermostConfigSet) {
		if (this.outermostConfigSet && !outermostConfigSet) {
			throw new IllegalStateException();
		}

		assert !outermostConfigSet || !dipsIntoOuterContext;
		this.outermostConfigSet = outermostConfigSet;
		invalidateHash();
	}

	public Set<ATNState> getStates() {
		Set<ATNState> states = new HashSet<>();
		for (ATNConfig c : this.configs) {
			states.add(c.getState());
		}

		return states;
	}

	public void optimizeConfigs(ATNSimulator interpreter) {
		final int n = configs.size();
		if (n == 0) {
			return;
		}

		boolean changed = false;
		final ATN atn = interpreter.atn;
		// 2-slot register cache eliminates redundant ConcurrentHashMap.get
		// probes when adjacent configs share the same PredictionContext.
		PredictionContext lastRaw1 = null;
		PredictionContext lastCached1 = null;
		PredictionContext lastRaw2 = null;
		PredictionContext lastCached2 = null;
		for (int i = 0; i < n; i++) {
			ATNConfig config = configs.get(i);
			PredictionContext ctx = config.getContext();
			if (!ctx.isEmpty()) {
				PredictionContext cached;
				if (ctx == lastRaw1) {
					cached = lastCached1;
				}
				else if (ctx == lastRaw2) {
					cached = lastCached2;
				}
				else {
					cached = atn.getCachedContext(ctx);
					lastRaw2 = lastRaw1;
					lastCached2 = lastCached1;
					lastRaw1 = ctx;
					lastCached1 = cached;
				}
				if (cached != ctx) {
					config.setContext(cached);
					changed = true;
				}
			}
		}
		if (changed) {
			invalidateHash();
		}
	}

	public ATNConfigSet clone(boolean readonly) {
		ATNConfigSet copy = new ATNConfigSet(this, readonly);
		if (!readonly && this.isReadOnly()) {
			copy.addAll(this.configs);
		}

		return copy;
	}

	@Override
	public int size() {
		return configs.size();
	}

	@Override
	public boolean isEmpty() {
		return configs.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		if (!(o instanceof ATNConfig)) {
			return false;
		}

		// Read-only DFA sets drop the merge index; fall back to linear scan.
		if (mergedConfigs == null) {
			for (int i = 0, n = configs.size(); i < n; i++) {
				if (configs.get(i).contains((ATNConfig)o)) {
					return true;
				}
			}
			return false;
		}

		ATNConfig config = (ATNConfig)o;
		long configKey = getKey(config);
		int mapIndex = mergedConfigs.indexOf(configKey);
		if (mapIndex >= 0) {
			ATNConfig mergedConfig = mergedConfigs.indexGet(mapIndex);
			if (canMerge(config, configKey, mergedConfig)) {
				return mergedConfig.contains(config);
			}
		}

		for (int i = 0, n = unmerged.size(); i < n; i++) {
			if (unmerged.get(i).contains(config)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Iterator<ATNConfig> iterator() {
		return new ATNConfigSetIterator();
	}

	@Override
	public Object[] toArray() {
		return configs.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return configs.toArray(a);
	}

	@Override
	public boolean add(ATNConfig e) {
		return add(e, null);
	}

	public boolean add(ATNConfig e, @Nullable PredictionContextCache contextCache) {
		ensureWritable();
		assert !outermostConfigSet || !e.getReachesIntoOuterContext();

		if (contextCache == null) {
			contextCache = PredictionContextCache.UNCACHED;
		}

		// PERF: Single probe via indexOf — avoids hashing the packed (state, alt)
		// key twice on the common get-then-put path. indexInsert reuses the slot
		// computed when the key was absent.
		final long key = getKey(e);
		final int mapIndex = mergedConfigs.indexOf(key);
		final boolean addKey = mapIndex < 0;
		if (!addKey) {
			ATNConfig mergedConfig = mergedConfigs.indexGet(mapIndex);
			if (canMerge(e, key, mergedConfig)) {
				return !mergeConfigContext(e, e.getContext(), contextCache, mergedConfig);
			}
		}

		for (int i = 0, n = unmerged.size(); i < n; i++) {
			ATNConfig unmergedConfig = unmerged.get(i);
			if (canMerge(e, key, unmergedConfig)) {
				if (mergeConfigContext(e, e.getContext(), contextCache, unmergedConfig)) return false;
				if (addKey) {
					mergedConfigs.indexInsert(mapIndex, key, unmergedConfig);
					unmerged.remove(i);
				}
				return true;
			}
		}

		configs.add(e);
		if (addKey) {
			mergedConfigs.indexInsert(mapIndex, key, e);
		} else {
			unmerged.add(e);
		}

		updatePropertiesForAddedConfig(e);
		invalidateHash();
		return true;
	}

	/**
	 * Zero-allocation merge-on-hit variant of {@link #add(ATNConfig, PredictionContextCache)}
	 * for {@link EpsilonClosure} and {@link ReachComputation}. Probes by
	 * {@code (targetState, template.getAlt())} before allocating an
	 * {@link ATNConfig}; on miss always materializes a fresh transformed copy
	 * (never aliasing {@code template}). Subclasses fall back to {@link #add}.
	 */
	boolean addTransformed(@NotNull ATNState targetState,
						   @NotNull ATNConfig template,
						   @Nullable PredictionContextCache contextCache)
	{
		return addTransformed(targetState, template, template.getContext(), contextCache);
	}

	/**
	 * Zero-allocation merge-on-hit variant with an explicit {@code incomingContext}
	 * (used for BFS rule transitions in {@link EpsilonClosure}).
	 */
	boolean addTransformed(@NotNull ATNState targetState,
						   @NotNull ATNConfig template,
						   @NotNull PredictionContext incomingContext,
						   @Nullable PredictionContextCache contextCache)
	{
		if (getClass() != ATNConfigSet.class) {
			ATNConfig transformed = incomingContext == template.getContext()
				? template.transformParser(targetState)
				: template.transformParser(targetState, incomingContext);
			return add(transformed, contextCache);
		}

		ensureWritable();
		assert !outermostConfigSet || !template.getReachesIntoOuterContext();

		if (contextCache == null) {
			contextCache = PredictionContextCache.UNCACHED;
		}

		final long key = keyOf(targetState.stateNumber, template.getAlt());
		final int mapIndex = mergedConfigs.indexOf(key);
		final boolean addKey = mapIndex < 0;
		final SemanticContext ls = template.getSemanticContext();
		if (!addKey) {
			ATNConfig mergedConfig = mergedConfigs.indexGet(mapIndex);
			SemanticContext rs = mergedConfig.getSemanticContext();
			if (ls == rs || (ls != null && ls.equals(rs))) {
				return !mergeConfigContext(template, incomingContext, contextCache, mergedConfig);
			}
		}

		for (int i = 0, n = unmerged.size(); i < n; i++) {
			ATNConfig unmergedConfig = unmerged.get(i);
			if (key == keyOf(unmergedConfig.getState().stateNumber, unmergedConfig.getAlt())) {
				SemanticContext rs = unmergedConfig.getSemanticContext();
				if (ls == rs || (ls != null && ls.equals(rs))) {
					if (mergeConfigContext(template, incomingContext, contextCache, unmergedConfig)) return false;
					if (addKey) {
						mergedConfigs.indexInsert(mapIndex, key, unmergedConfig);
						unmerged.remove(i);
					}
					return true;
				}
			}
		}

		// On miss, ALWAYS materialize a new ATNConfig so template is never
		// mutated in place by later merges into this set.
		ATNConfig e = incomingContext == template.getContext()
			? template.transformParser(targetState)
			: template.transformParser(targetState, incomingContext);
		configs.add(e);
		if (addKey) {
			mergedConfigs.indexInsert(mapIndex, key, e);
		} else {
			unmerged.add(e);
		}

		updatePropertiesForAddedConfig(e);
		invalidateHash();
		return true;
	}

	private boolean mergeConfigContext(ATNConfig e,
									   PredictionContext incomingCtx,
									   @Nullable PredictionContextCache contextCache,
									   ATNConfig unmergedConfig)
	{
		final int eDepth = e.getOuterContextDepth();
		final int existingDepth = unmergedConfig.getOuterContextDepth();
		if (eDepth > existingDepth) {
			unmergedConfig.setOuterContextDepth(eDepth);
			if (existingDepth == 0) {
				invalidateHash();
			}
		}
		if (e.isPrecedenceFilterSuppressed() && !unmergedConfig.isPrecedenceFilterSuppressed()) {
			unmergedConfig.setPrecedenceFilterSuppressed(true);
		}

		final PredictionContext existingCtx = unmergedConfig.getContext();
		updatePropertiesForMergedConfig(e);
		if (existingCtx == incomingCtx) {
			return true;
		}

		final PredictionContext joined = contextCache != null
			? contextCache.join(existingCtx, incomingCtx)
			: PredictionContext.join(existingCtx, incomingCtx, PredictionContextCache.UNCACHED);
		if (existingCtx == joined || existingCtx.equals(joined)) {
			return true;
		}

		unmergedConfig.setContext(joined);
		invalidateHash();
		return false;
	}

	private void updatePropertiesForMergedConfig(ATNConfig config) {
		// merged configs can't change the alt or semantic context
		dipsIntoOuterContext |= config.getReachesIntoOuterContext();
		assert !outermostConfigSet || !dipsIntoOuterContext;
	}

	private void updatePropertiesForAddedConfig(ATNConfig config) {
		if (configs.size() == 1) {
			uniqueAlt = config.getAlt();
		} else if (uniqueAlt != config.getAlt()) {
			uniqueAlt = ATN.INVALID_ALT_NUMBER;
		}

		SemanticContext sem = config.getSemanticContext();
		hasSemanticContext |= sem != SemanticContext.NONE && !SemanticContext.NONE.equals(sem);
		dipsIntoOuterContext |= config.getReachesIntoOuterContext();
		assert !outermostConfigSet || !dipsIntoOuterContext;
	}

	/**
	 * Whether {@code left} may merge into the existing {@code right} config.
	 *
	 * <p>PERF: The packed {@code leftKey} already encodes
	 * {@code (stateNumber, alt)} (see {@link #getKey}). Comparing keys alone
	 * establishes state+alt equality; a separate
	 * {@code getState().stateNumber} walk is redundant on the hot merge path.</p>
	 */
	protected boolean canMerge(ATNConfig left, long leftKey, ATNConfig right) {
		if (leftKey != getKey(right)) {
			return false;
		}

		SemanticContext ls = left.getSemanticContext();
		SemanticContext rs = right.getSemanticContext();
		return ls == rs || (ls != null && ls.equals(rs));
	}

	private static long keyOf(int stateNumber, int alt) {
		long key = stateNumber & 0xFFFFFFFFL;
		return (key << 24) | (alt & 0xFFFFFF);
	}

	/**
	 * Packed {@code (stateNumber, alt)} key. The alternative keeps the full
	 * 24 bits stored by {@link ATNConfig}.
	 */
	protected long getKey(ATNConfig e) {
		return keyOf(e.getState().stateNumber, e.getAlt());
	}

	@Override
	public boolean remove(Object o) {
		ensureWritable();

		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for (Object o : c) {
			if (!(o instanceof ATNConfig)) {
				return false;
			}

			if (!contains(o)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public boolean addAll(Collection<? extends ATNConfig> c) {
		return addAll(c, null);
	}

	public boolean addAll(Collection<? extends ATNConfig> c, PredictionContextCache contextCache) {
		ensureWritable();

		boolean changed = false;
		for (ATNConfig group : c) {
			changed |= add(group, contextCache);
		}

		return changed;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		ensureWritable();
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		ensureWritable();
		throw new UnsupportedOperationException("Not supported yet.");
	}

	/**
	 * Removes all configurations and resets working-set flags so the instance
	 * can be reused as hot-path scratch (see {@link RetainedConfigSet}).
	 *
	 * <p>In particular {@link #outermostConfigSet} is cleared: retention across
	 * predictions would otherwise leave a sticky outermost flag and trip
	 * {@link #add} / {@link #setOutermostConfigSet} asserts on later edges.</p>
	 *
	 * <p><strong>PERF:</strong> Empty clear is O(1) for the merge map (no bulk
	 * {@code Arrays.fill}) and O(1) for empty {@link ArrayList}s. When the set
	 * holds few configs relative to the grown open-addressed table
	 * ({@code size * }{@link #SPARSE_CLEAR_CAPACITY_FACTOR}{@code < tableLength}),
	 * known merge keys are removed individually (O(n)) instead of filling the
	 * entire table (O(capacity)). Dense sets still use bulk clear. List clear
	 * only nulls the used prefix (JDK {@link ArrayList#clear}), never the full
	 * capacity. Together these policies remove the double full-table zeroing
	 * that dominated lexer {@code computeTargetState} pool obtain/release on
	 * many-core workloads.</p>
	 */
	@Override
	public void clear() {
		ensureWritable();

		final int n = configs.size();
		if (n == 0 && unmerged.isEmpty() && mergedConfigs.isEmpty()) {
			// Already empty storage: still reset flags (e.g. outermost set on an
			// empty scratch after setOutermostConfigSet) without touching tables.
			resetWorkingFlags();
			return;
		}

		// Prefer sparse key removal when occupancy is low vs table capacity.
		// All merge-map keys are exactly getKey(c) for some c in configs (map
		// holds one representative per key; unmerged siblings share that key).
		// Removing every configs key therefore empties the map; duplicate keys
		// among unmerged configs make remove a cheap no-op the second time.
		// Use long arithmetic so n * factor cannot overflow int for huge sets.
		if (n > 0
			&& (long)n * SPARSE_CLEAR_CAPACITY_FACTOR < mergedConfigs.tableLength()) {
			for (int i = 0; i < n; i++) {
				mergedConfigs.remove(getKey(configs.get(i)));
			}
			// Hardening: if a key ever diverged from getKey(c) after insert
			// (e.g. OrderedATNConfigSet keyed by hashCode while a future
			// mutator changed a hash-participating field without re-indexing),
			// fall back to bulk clear so scratch reuse cannot leak entries.
			if (!mergedConfigs.isEmpty()) {
				mergedConfigs.clear();
			}
		}
		else {
			mergedConfigs.clear();
		}

		// ArrayList.clear nulls only [0, size) — used prefix, not full capacity.
		unmerged.clear();
		configs.clear();
		resetWorkingFlags();
	}

	/**
	 * Resets conflict / context flags used while a writable set is being built.
	 * Invoked by {@link #clear()} after storage is empty (or was already empty).
	 */
	private void resetWorkingFlags() {
		dipsIntoOuterContext = false;
		hasSemanticContext = false;
		outermostConfigSet = false;
		uniqueAlt = ATN.INVALID_ALT_NUMBER;
		conflictInfo = null;
		invalidateHash();
	}

	private void invalidateHash() {
		cachedHashCode = 0;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof ATNConfigSet)) {
			return false;
		}

		ATNConfigSet other = (ATNConfigSet)obj;
		if (this.outermostConfigSet != other.outermostConfigSet) {
			return false;
		}
		int h1 = this.cachedHashCode;
		int h2 = other.cachedHashCode;
		if (h1 != 0 && h2 != 0 && h1 != h2) {
			return false;
		}
		if (!Utils.equals(conflictInfo, other.conflictInfo)) {
			return false;
		}
		final ArrayList<ATNConfig> left = this.configs;
		final ArrayList<ATNConfig> right = other.configs;
		final int n = left.size();
		if (n != right.size()) {
			return false;
		}
		// Indexed loop avoids allocating two ArrayList$Itr objects on JDK 8.
		for (int i = 0; i < n; i++) {
			ATNConfig a = left.get(i);
			ATNConfig b = right.get(i);
			if (a != b && (a == null || !a.equals(b))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hashCode = cachedHashCode;
		if (hashCode != 0) {
			return hashCode;
		}
		return computeHashCode();
	}

	private int computeHashCode() {
		// Indexed loop matches ArrayList/AbstractList.hashCode() exactly without
		// allocating an ArrayList$Itr on JDK 8. Guarded on exact ArrayList class
		// so subclass overrides (e.g. tests) still run their custom hashCode().
		final ArrayList<ATNConfig> list = this.configs;
		int listHash;
		if (list.getClass() == ArrayList.class) {
			listHash = 1;
			for (int i = 0, n = list.size(); i < n; i++) {
				ATNConfig c = list.get(i);
				listHash = 31 * listHash + (c == null ? 0 : c.hashCode());
			}
		}
		else {
			listHash = list.hashCode();
		}

		int hashCode = 1;
		hashCode = 5 * hashCode ^ (outermostConfigSet ? 1 : 0);
		hashCode = 5 * hashCode ^ listHash;
		if (hashCode == 0) {
			hashCode = 1;
		}
		cachedHashCode = hashCode;
		return hashCode;
	}

	@Override
	public String toString() {
		return toString(false);
	}

	public String toString(boolean showContext) {
		StringBuilder buf = new StringBuilder();
		List<ATNConfig> sortedConfigs = new ArrayList<>(configs);
		sortedConfigs.sort((o1, o2) -> {
			if (o1.getAlt() != o2.getAlt()) {
				return o1.getAlt() - o2.getAlt();
			} else if (o1.getState().stateNumber != o2.getState().stateNumber) {
				return o1.getState().stateNumber - o2.getState().stateNumber;
			} else {
				return o1.getSemanticContext().toString().compareTo(o2.getSemanticContext().toString());
			}
		});

		buf.append("[");
		for (int i = 0; i < sortedConfigs.size(); i++) {
			if (i > 0) {
				buf.append(", ");
			}
			buf.append(sortedConfigs.get(i).toString(null, true, showContext));
		}
		buf.append("]");

		if ( hasSemanticContext ) buf.append(",hasSemanticContext=").append(hasSemanticContext);
		if ( uniqueAlt!=ATN.INVALID_ALT_NUMBER ) buf.append(",uniqueAlt=").append(uniqueAlt);
		if ( conflictInfo!=null ) {
			buf.append(",conflictingAlts=").append(conflictInfo.getConflictedAlts());
			if (!conflictInfo.isExact()) {
				buf.append("*");
			}
		}
		if ( dipsIntoOuterContext ) buf.append(",dipsIntoOuterContext");
		return buf.toString();
	}

	public int getUniqueAlt() {
		return uniqueAlt;
	}

	public boolean hasSemanticContext() {
		return hasSemanticContext;
	}

	public void clearExplicitSemanticContext() {
		ensureWritable();
		hasSemanticContext = false;
	}

	public void markExplicitSemanticContext() {
		ensureWritable();
		hasSemanticContext = true;
	}

	public ConflictInfo getConflictInfo() {
		return conflictInfo;
	}

	public void setConflictInfo(ConflictInfo conflictInfo) {
		ensureWritable();
		this.conflictInfo = conflictInfo;
	}

	public BitSet getConflictingAlts() {
		if (conflictInfo == null) {
			return null;
		}

		return conflictInfo.getConflictedAlts();
	}

	public boolean isExactConflict() {
		if (conflictInfo == null) {
			return false;
		}

		return conflictInfo.isExact();
	}

	public boolean getDipsIntoOuterContext() {
		return dipsIntoOuterContext;
	}

	public ATNConfig get(int index) {
		return configs.get(index);
	}

	public void remove(int index) {
		ensureWritable();
		final ATNConfig config = configs.remove(index);
		invalidateHash();
		long key = getKey(config);
		int mapIndex = mergedConfigs.indexOf(key);
		if (mapIndex >= 0 && mergedConfigs.indexGet(mapIndex) == config) {
			mergedConfigs.indexRemove(mapIndex);
		} else {
			for (int i = 0, n = unmerged.size(); i < n; i++) {
				if (unmerged.get(i) == config) {
					unmerged.remove(i);
					return;
				}
			}
		}
	}

	protected final void ensureWritable() {
		if (isReadOnly()) {
			throw new IllegalStateException("This ATNConfigSet is read only.");
		}
	}

	private final class ATNConfigSetIterator implements Iterator<ATNConfig> {

		int index = -1;
		boolean removed = false;

		@Override
		public boolean hasNext() {
			return index + 1 < configs.size();
		}

		@Override
		public ATNConfig next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}

			index++;
			removed = false;
			return configs.get(index);
		}

		@Override
		public void remove() {
			if (removed || index < 0 || index >= configs.size()) {
				throw new IllegalStateException();
			}

			ATNConfigSet.this.remove(index);
			removed = true;
		}

	}
}
