/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector;

import java.sql.Timestamp;
import java.util.Arrays;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.common.type.HiveIntervalDayTime;
import org.apache.hadoop.hive.ql.exec.KeyWrapper;
import org.apache.hadoop.hive.ql.exec.vector.expressions.StringExpr;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.util.JavaDataModel;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;

/**
 * A hash map key wrapper for vectorized processing.
 * It stores the key values as primitives in arrays for each supported primitive type.
 * This works in conjunction with
 * {@link org.apache.hadoop.hive.ql.exec.VectorHashKeyWrapperBatch VectorHashKeyWrapperBatch}
 * to hash vectorized processing units (batches).
 */
public class VectorHashKeyWrapper extends KeyWrapper {

  private static final int[] EMPTY_INT_ARRAY = new int[0];
  private static final long[] EMPTY_LONG_ARRAY = new long[0];
  private static final double[] EMPTY_DOUBLE_ARRAY = new double[0];
  private static final byte[][] EMPTY_BYTES_ARRAY = new byte[0][];
  private static final HiveDecimalWritable[] EMPTY_DECIMAL_ARRAY = new HiveDecimalWritable[0];
  private static final Timestamp[] EMPTY_TIMESTAMP_ARRAY = new Timestamp[0];
  private static final HiveIntervalDayTime[] EMPTY_INTERVAL_DAY_TIME_ARRAY = new HiveIntervalDayTime[0];

  private long[] longValues;
  private double[] doubleValues;

  private byte[][] byteValues;
  private int[] byteStarts;
  private int[] byteLengths;

  private HiveDecimalWritable[] decimalValues;

  private Timestamp[] timestampValues;

  private HiveIntervalDayTime[] intervalDayTimeValues;

  private boolean[] isNull;
  private int hashcode;

  public VectorHashKeyWrapper(int longValuesCount, int doubleValuesCount,
          int byteValuesCount, int decimalValuesCount, int timestampValuesCount,
          int intervalDayTimeValuesCount) {
    longValues = longValuesCount > 0 ? new long[longValuesCount] : EMPTY_LONG_ARRAY;
    doubleValues = doubleValuesCount > 0 ? new double[doubleValuesCount] : EMPTY_DOUBLE_ARRAY;
    decimalValues = decimalValuesCount > 0 ? new HiveDecimalWritable[decimalValuesCount] : EMPTY_DECIMAL_ARRAY;
    timestampValues = timestampValuesCount > 0 ? new Timestamp[timestampValuesCount] : EMPTY_TIMESTAMP_ARRAY;
    intervalDayTimeValues = intervalDayTimeValuesCount > 0 ? new HiveIntervalDayTime[intervalDayTimeValuesCount] : EMPTY_INTERVAL_DAY_TIME_ARRAY;
    for(int i = 0; i < decimalValuesCount; ++i) {
      decimalValues[i] = new HiveDecimalWritable(HiveDecimal.ZERO);
    }
    if (byteValuesCount > 0) {
      byteValues = new byte[byteValuesCount][];
      byteStarts = new int[byteValuesCount];
      byteLengths = new int[byteValuesCount];
    } else {
      byteValues = EMPTY_BYTES_ARRAY;
      byteStarts = EMPTY_INT_ARRAY;
      byteLengths = EMPTY_INT_ARRAY;
    }
    for(int i = 0; i < timestampValuesCount; ++i) {
      timestampValues[i] = new Timestamp(0);
    }
    for(int i = 0; i < intervalDayTimeValuesCount; ++i) {
      intervalDayTimeValues[i] = new HiveIntervalDayTime();
    }
    isNull = new boolean[longValuesCount + doubleValuesCount + byteValuesCount +
                         decimalValuesCount + timestampValuesCount + intervalDayTimeValuesCount];
    hashcode = 0;
  }

  private VectorHashKeyWrapper() {
  }

  @Override
  public void getNewKey(Object row, ObjectInspector rowInspector) throws HiveException {
    throw new HiveException("Should not be called");
  }

  @Override
  public void setHashKey() {
    hashcode = Arrays.hashCode(longValues) ^
        Arrays.hashCode(doubleValues) ^
        Arrays.hashCode(isNull);

    for (int i = 0; i < decimalValues.length; i++) {
      // Use the new faster hash code since we are hashing memory objects.
      hashcode ^= decimalValues[i].newFasterHashCode();
    }

    for (int i = 0; i < timestampValues.length; i++) {
      hashcode ^= timestampValues[i].hashCode();
    }

    for (int i = 0; i < intervalDayTimeValues.length; i++) {
      hashcode ^= intervalDayTimeValues[i].hashCode();
    }

    // This code, with branches and all, is not executed if there are no string keys
    for (int i = 0; i < byteValues.length; ++i) {
      /*
       *  Hashing the string is potentially expensive so is better to branch.
       *  Additionally not looking at values for nulls allows us not reset the values.
       */
      if (!isNull[longValues.length + doubleValues.length + i]) {
        byte[] bytes = byteValues[i];
        int start = byteStarts[i];
        int length = byteLengths[i];
        if (length == bytes.length && start == 0) {
          hashcode ^= Arrays.hashCode(bytes);
        }
        else {
          // Unfortunately there is no Arrays.hashCode(byte[], start, length)
          for(int j = start; j < start + length; ++j) {
            // use 461 as is a (sexy!) prime.
            hashcode ^= 461 * bytes[j];
          }
        }
      }
    }
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  @Override
  public boolean equals(Object that) {
    if (that instanceof VectorHashKeyWrapper) {
      VectorHashKeyWrapper keyThat = (VectorHashKeyWrapper)that;
      return hashcode == keyThat.hashcode &&
          Arrays.equals(longValues, keyThat.longValues) &&
          Arrays.equals(doubleValues, keyThat.doubleValues) &&
          Arrays.equals(decimalValues,  keyThat.decimalValues) &&
          Arrays.equals(timestampValues,  keyThat.timestampValues) &&
          Arrays.equals(intervalDayTimeValues,  keyThat.intervalDayTimeValues) &&
          Arrays.equals(isNull, keyThat.isNull) &&
          byteValues.length == keyThat.byteValues.length &&
          (0 == byteValues.length || bytesEquals(keyThat));
    }
    return false;
  }

  private boolean bytesEquals(VectorHashKeyWrapper keyThat) {
    //By the time we enter here the byteValues.lentgh and isNull must have already been compared
    for (int i = 0; i < byteValues.length; ++i) {
      // the byte comparison is potentially expensive so is better to branch on null
      if (!isNull[longValues.length + doubleValues.length + i]) {
        if (!StringExpr.equal(
            byteValues[i],
            byteStarts[i],
            byteLengths[i],
            keyThat.byteValues[i],
            keyThat.byteStarts[i],
            keyThat.byteLengths[i])) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  protected Object clone() {
    VectorHashKeyWrapper clone = new VectorHashKeyWrapper();
    duplicateTo(clone);
    return clone;
  }

  public void duplicateTo(VectorHashKeyWrapper clone) {
    clone.longValues = (longValues.length > 0) ? longValues.clone() : EMPTY_LONG_ARRAY;
    clone.doubleValues = (doubleValues.length > 0) ? doubleValues.clone() : EMPTY_DOUBLE_ARRAY;
    clone.isNull = isNull.clone();

    if (decimalValues.length > 0) {
      // Decimal columns use HiveDecimalWritable.
      clone.decimalValues = new HiveDecimalWritable[decimalValues.length];
      for(int i = 0; i < decimalValues.length; ++i) {
        clone.decimalValues[i] = new HiveDecimalWritable(decimalValues[i]);
      }
    } else {
      clone.decimalValues = EMPTY_DECIMAL_ARRAY;
    }

    if (byteLengths.length > 0) {
      clone.byteValues = new byte[byteValues.length][];
      clone.byteStarts = new int[byteValues.length];
      clone.byteLengths = byteLengths.clone();
      for (int i = 0; i < byteValues.length; ++i) {
        // avoid allocation/copy of nulls, because it potentially expensive.
        // branch instead.
        if (!isNull[longValues.length + doubleValues.length + i]) {
          clone.byteValues[i] = Arrays.copyOfRange(byteValues[i],
              byteStarts[i], byteStarts[i] + byteLengths[i]);
        }
      }
    } else {
      clone.byteValues = EMPTY_BYTES_ARRAY;
      clone.byteStarts = EMPTY_INT_ARRAY;
      clone.byteLengths = EMPTY_INT_ARRAY;
    }
    if (timestampValues.length > 0) {
      clone.timestampValues = new Timestamp[timestampValues.length];
      for(int i = 0; i < timestampValues.length; ++i) {
        clone.timestampValues[i] = (Timestamp) timestampValues[i].clone();
      }
    } else {
      clone.timestampValues = EMPTY_TIMESTAMP_ARRAY;
    }
    if (intervalDayTimeValues.length > 0) {
      clone.intervalDayTimeValues = new HiveIntervalDayTime[intervalDayTimeValues.length];
      for(int i = 0; i < intervalDayTimeValues.length; ++i) {
        clone.intervalDayTimeValues[i] = (HiveIntervalDayTime) intervalDayTimeValues[i].clone();
      }
    } else {
      clone.intervalDayTimeValues = EMPTY_INTERVAL_DAY_TIME_ARRAY;
    }

    clone.hashcode = hashcode;
    assert clone.equals(this);
  }

  @Override
  public KeyWrapper copyKey() {
    return (KeyWrapper) clone();
  }

  @Override
  public void copyKey(KeyWrapper oldWrapper) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object[] getKeyArray() {
    throw new UnsupportedOperationException();
  }

  public void assignDouble(int index, double d) {
    doubleValues[index] = d;
    isNull[longValues.length + index] = false;
  }

  public void assignNullDouble(int index) {
    doubleValues[index] = 0; // assign 0 to simplify hashcode
    isNull[longValues.length + index] = true;
  }

  public void assignLong(int index, long v) {
    longValues[index] = v;
    isNull[index] = false;
  }

  public void assignNullLong(int index) {
    longValues[index] = 0; // assign 0 to simplify hashcode
    isNull[index] = true;
  }

  public void assignString(int index, byte[] bytes, int start, int length) {
    byteValues[index] = bytes;
    byteStarts[index] = start;
    byteLengths[index] = length;
    isNull[longValues.length + doubleValues.length + index] = false;
  }

  public void assignNullString(int index) {
    // We do not assign the value to byteValues[] because the value is never used on null
    isNull[longValues.length + doubleValues.length + index] = true;
  }

  public void assignDecimal(int index, HiveDecimalWritable value) {
    decimalValues[index].set(value);
    isNull[longValues.length + doubleValues.length + byteValues.length + index] = false;
  }

  public void assignNullDecimal(int index) {
      isNull[longValues.length + doubleValues.length + byteValues.length + index] = true;
  }

  public void assignTimestamp(int index, Timestamp value) {
    timestampValues[index] = value;
    isNull[longValues.length + doubleValues.length + byteValues.length +
           decimalValues.length + index] = false;
  }

  public void assignTimestamp(int index, TimestampColumnVector colVector, int elementNum) {
    colVector.timestampUpdate(timestampValues[index], elementNum);
    isNull[longValues.length + doubleValues.length + byteValues.length +
           decimalValues.length + index] = false;
  }

  public void assignNullTimestamp(int index) {
      isNull[longValues.length + doubleValues.length + byteValues.length +
             decimalValues.length + index] = true;
  }

  public void assignIntervalDayTime(int index, HiveIntervalDayTime value) {
    intervalDayTimeValues[index].set(value);
    isNull[longValues.length + doubleValues.length + byteValues.length +
           decimalValues.length + timestampValues.length + index] = false;
  }

  public void assignIntervalDayTime(int index, IntervalDayTimeColumnVector colVector, int elementNum) {
    intervalDayTimeValues[index].set(colVector.asScratchIntervalDayTime(elementNum));
    isNull[longValues.length + doubleValues.length + byteValues.length +
           decimalValues.length + timestampValues.length + index] = false;
  }

  public void assignNullIntervalDayTime(int index) {
      isNull[longValues.length + doubleValues.length + byteValues.length +
             decimalValues.length + timestampValues.length + index] = true;
  }

  @Override
  public String toString()
  {
    return String.format("%d[%s] %d[%s] %d[%s] %d[%s] %d[%s] %d[%s]",
        longValues.length, Arrays.toString(longValues),
        doubleValues.length, Arrays.toString(doubleValues),
        byteValues.length, Arrays.toString(byteValues),
        decimalValues.length, Arrays.toString(decimalValues),
        timestampValues.length, Arrays.toString(timestampValues),
        intervalDayTimeValues.length, Arrays.toString(intervalDayTimeValues));
  }

  public boolean getIsLongNull(int i) {
    return isNull[i];
  }

  public boolean getIsDoubleNull(int i) {
    return isNull[longValues.length + i];
  }

  public boolean getIsBytesNull(int i) {
    return isNull[longValues.length + doubleValues.length + i];
  }


  public long getLongValue(int i) {
    return longValues[i];
  }

  public double getDoubleValue(int i) {
    return doubleValues[i];
  }

  public byte[] getBytes(int i) {
    return byteValues[i];
  }

  public int getByteStart(int i) {
    return byteStarts[i];
  }

  public int getByteLength(int i) {
    return byteLengths[i];
  }

  public int getVariableSize() {
    int variableSize = 0;
    for (int i=0; i<byteLengths.length; ++i) {
      JavaDataModel model = JavaDataModel.get();
      variableSize += model.lengthForByteArrayOfSize(byteLengths[i]);
    }
    return variableSize;
  }

  public boolean getIsDecimalNull(int i) {
    return isNull[longValues.length + doubleValues.length + byteValues.length + i];
  }

  public HiveDecimalWritable getDecimal(int i) {
    return decimalValues[i];
  }

  public boolean getIsTimestampNull(int i) {
    return isNull[longValues.length + doubleValues.length + byteValues.length +
                  decimalValues.length + i];
  }

  public Timestamp getTimestamp(int i) {
    return timestampValues[i];
  }

  public boolean getIsIntervalDayTimeNull(int i) {
    return isNull[longValues.length + doubleValues.length + byteValues.length +
                  decimalValues.length + timestampValues.length + i];
  }

  public HiveIntervalDayTime getIntervalDayTime(int i) {
    return intervalDayTimeValues[i];
  }
}

