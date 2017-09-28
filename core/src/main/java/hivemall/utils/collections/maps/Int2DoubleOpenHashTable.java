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
package hivemall.utils.collections.maps;

import hivemall.utils.math.Primes;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;

/**
 * An open-addressing hash table using double hashing.
 *
 * <pre>
 * Primary hash function: h1(k) = k mod m
 * Secondary hash function: h2(k) = 1 + (k mod(m-2))
 * </pre>
 *
 * @see http://en.wikipedia.org/wiki/Double_hashing
 */
public class Int2DoubleOpenHashTable implements Externalizable {

    protected static final byte FREE = 0;
    protected static final byte FULL = 1;
    protected static final byte REMOVED = 2;

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final float DEFAULT_GROW_FACTOR = 2.0f;

    protected final transient float _loadFactor;
    protected final transient float _growFactor;

    protected int _used = 0;
    protected int _threshold;
    protected double defaultReturnValue = -1.d;

    protected int[] _keys;
    protected double[] _values;
    protected byte[] _states;

    protected Int2DoubleOpenHashTable(int size, float loadFactor, float growFactor,
            boolean forcePrime) {
        if (size < 1) {
            throw new IllegalArgumentException();
        }
        this._loadFactor = loadFactor;
        this._growFactor = growFactor;
        int actualSize = forcePrime ? Primes.findLeastPrimeNumber(size) : size;
        this._keys = new int[actualSize];
        this._values = new double[actualSize];
        this._states = new byte[actualSize];
        this._threshold = (int) (actualSize * _loadFactor);
    }

    public Int2DoubleOpenHashTable(int size, float loadFactor, float growFactor) {
        this(size, loadFactor, growFactor, true);
    }

    public Int2DoubleOpenHashTable(int size) {
        this(size, DEFAULT_LOAD_FACTOR, DEFAULT_GROW_FACTOR, true);
    }

    /**
     * Only for {@link Externalizable}
     */
    public Int2DoubleOpenHashTable() {// required for serialization
        this._loadFactor = DEFAULT_LOAD_FACTOR;
        this._growFactor = DEFAULT_GROW_FACTOR;
    }

    public void defaultReturnValue(double v) {
        this.defaultReturnValue = v;
    }

    public boolean containsKey(final int key) {
        return findKey(key) >= 0;
    }

    /**
     * @return -1.d if not found
     */
    public double get(final int key) {
        return get(key, defaultReturnValue);
    }

    public double get(final int key, final double defaultValue) {
        final int i = findKey(key);
        if (i < 0) {
            return defaultValue;
        }
        return _values[i];
    }

    public double put(final int key, final double value) {
        final int hash = keyHash(key);
        int keyLength = _keys.length;
        int keyIdx = hash % keyLength;

        boolean expanded = preAddEntry(keyIdx);
        if (expanded) {
            keyLength = _keys.length;
            keyIdx = hash % keyLength;
        }

        final int[] keys = _keys;
        final double[] values = _values;
        final byte[] states = _states;

        if (states[keyIdx] == FULL) {// double hashing
            if (keys[keyIdx] == key) {
                double old = values[keyIdx];
                values[keyIdx] = value;
                return old;
            }
            // try second hash
            final int decr = 1 + (hash % (keyLength - 2));
            for (;;) {
                keyIdx -= decr;
                if (keyIdx < 0) {
                    keyIdx += keyLength;
                }
                if (isFree(keyIdx, key)) {
                    break;
                }
                if (states[keyIdx] == FULL && keys[keyIdx] == key) {
                    double old = values[keyIdx];
                    values[keyIdx] = value;
                    return old;
                }
            }
        }
        keys[keyIdx] = key;
        values[keyIdx] = value;
        states[keyIdx] = FULL;
        ++_used;
        return defaultReturnValue;
    }

    /** Return weather the required slot is free for new entry */
    protected boolean isFree(final int index, final int key) {
        final byte stat = _states[index];
        if (stat == FREE) {
            return true;
        }
        if (stat == REMOVED && _keys[index] == key) {
            return true;
        }
        return false;
    }

    /** @return expanded or not */
    protected boolean preAddEntry(final int index) {
        if ((_used + 1) >= _threshold) {// too filled
            int newCapacity = Math.round(_keys.length * _growFactor);
            ensureCapacity(newCapacity);
            return true;
        }
        return false;
    }

    protected int findKey(final int key) {
        final int[] keys = _keys;
        final byte[] states = _states;
        final int keyLength = keys.length;

        final int hash = keyHash(key);
        int keyIdx = hash % keyLength;
        if (states[keyIdx] != FREE) {
            if (states[keyIdx] == FULL && keys[keyIdx] == key) {
                return keyIdx;
            }
            // try second hash
            final int decr = 1 + (hash % (keyLength - 2));
            for (;;) {
                keyIdx -= decr;
                if (keyIdx < 0) {
                    keyIdx += keyLength;
                }
                if (isFree(keyIdx, key)) {
                    return -1;
                }
                if (states[keyIdx] == FULL && keys[keyIdx] == key) {
                    return keyIdx;
                }
            }
        }
        return -1;
    }

    public double remove(final int key) {
        final int[] keys = _keys;
        final double[] values = _values;
        final byte[] states = _states;
        final int keyLength = keys.length;

        final int hash = keyHash(key);
        int keyIdx = hash % keyLength;
        if (states[keyIdx] != FREE) {
            if (states[keyIdx] == FULL && keys[keyIdx] == key) {
                double old = values[keyIdx];
                states[keyIdx] = REMOVED;
                --_used;
                return old;
            }
            //  second hash
            final int decr = 1 + (hash % (keyLength - 2));
            for (;;) {
                keyIdx -= decr;
                if (keyIdx < 0) {
                    keyIdx += keyLength;
                }
                if (states[keyIdx] == FREE) {
                    return defaultReturnValue;
                }
                if (states[keyIdx] == FULL && keys[keyIdx] == key) {
                    double old = values[keyIdx];
                    states[keyIdx] = REMOVED;
                    --_used;
                    return old;
                }
            }
        }
        return defaultReturnValue;
    }

    public int size() {
        return _used;
    }

    public void clear() {
        Arrays.fill(_states, FREE);
        this._used = 0;
    }

    public IMapIterator entries() {
        return new MapIterator();
    }

    @Override
    public String toString() {
        int len = size() * 10 + 2;
        StringBuilder buf = new StringBuilder(len);
        buf.append('{');
        IMapIterator i = entries();
        while (i.next() != -1) {
            buf.append(i.getKey());
            buf.append('=');
            buf.append(i.getValue());
            if (i.hasNext()) {
                buf.append(',');
            }
        }
        buf.append('}');
        return buf.toString();
    }

    protected void ensureCapacity(final int newCapacity) {
        int prime = Primes.findLeastPrimeNumber(newCapacity);
        rehash(prime);
        this._threshold = Math.round(prime * _loadFactor);
    }

    private void rehash(final int newCapacity) {
        int oldCapacity = _keys.length;
        if (newCapacity <= oldCapacity) {
            throw new IllegalArgumentException("new: " + newCapacity + ", old: " + oldCapacity);
        }
        final int[] newkeys = new int[newCapacity];
        final double[] newValues = new double[newCapacity];
        final byte[] newStates = new byte[newCapacity];
        int used = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (_states[i] == FULL) {
                used++;
                final int k = _keys[i];
                final double v = _values[i];
                final int hash = keyHash(k);
                int keyIdx = hash % newCapacity;
                if (newStates[keyIdx] == FULL) {// second hashing
                    int decr = 1 + (hash % (newCapacity - 2));
                    while (newStates[keyIdx] != FREE) {
                        keyIdx -= decr;
                        if (keyIdx < 0) {
                            keyIdx += newCapacity;
                        }
                    }
                }
                newkeys[keyIdx] = k;
                newValues[keyIdx] = v;
                newStates[keyIdx] = FULL;
            }
        }
        this._keys = newkeys;
        this._values = newValues;
        this._states = newStates;
        this._used = used;
    }

    private static int keyHash(int key) {
        return key & 0x7fffffff;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(_threshold);
        out.writeInt(_used);

        out.writeInt(_keys.length);
        IMapIterator i = entries();
        while (i.next() != -1) {
            out.writeInt(i.getKey());
            out.writeDouble(i.getValue());
        }
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        this._threshold = in.readInt();
        this._used = in.readInt();

        int keylen = in.readInt();
        int[] keys = new int[keylen];
        double[] values = new double[keylen];
        byte[] states = new byte[keylen];
        for (int i = 0; i < _used; i++) {
            int k = in.readInt();
            double v = in.readDouble();
            int hash = keyHash(k);
            int keyIdx = hash % keylen;
            if (states[keyIdx] != FREE) {// second hash
                int decr = 1 + (hash % (keylen - 2));
                for (;;) {
                    keyIdx -= decr;
                    if (keyIdx < 0) {
                        keyIdx += keylen;
                    }
                    if (states[keyIdx] == FREE) {
                        break;
                    }
                }
            }
            states[keyIdx] = FULL;
            keys[keyIdx] = k;
            values[keyIdx] = v;
        }
        this._keys = keys;
        this._values = values;
        this._states = states;
    }

    public interface IMapIterator {

        public boolean hasNext();

        /**
         * @return -1 if not found
         */
        public int next();

        public int getKey();

        public double getValue();

    }

    private final class MapIterator implements IMapIterator {

        int nextEntry;
        int lastEntry = -1;

        MapIterator() {
            this.nextEntry = nextEntry(0);
        }

        /** find the index of next full entry */
        int nextEntry(int index) {
            while (index < _keys.length && _states[index] != FULL) {
                index++;
            }
            return index;
        }

        public boolean hasNext() {
            return nextEntry < _keys.length;
        }

        public int next() {
            if (!hasNext()) {
                return -1;
            }
            int curEntry = nextEntry;
            this.lastEntry = curEntry;
            this.nextEntry = nextEntry(curEntry + 1);
            return curEntry;
        }

        public int getKey() {
            if (lastEntry == -1) {
                throw new IllegalStateException();
            }
            return _keys[lastEntry];
        }

        public double getValue() {
            if (lastEntry == -1) {
                throw new IllegalStateException();
            }
            return _values[lastEntry];
        }
    }
}