/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.nodes.vector;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;
import com.oracle.truffle.llvm.runtime.vector.LLVMDoubleVector;
import com.oracle.truffle.llvm.runtime.vector.LLVMFloatVector;
import com.oracle.truffle.llvm.runtime.vector.LLVMI16Vector;
import com.oracle.truffle.llvm.runtime.vector.LLVMI32Vector;
import com.oracle.truffle.llvm.runtime.vector.LLVMI64Vector;
import com.oracle.truffle.llvm.runtime.vector.LLVMI8Vector;
import com.oracle.truffle.llvm.runtime.vector.LLVMPointerVector;

@NodeChild(value = "left")
@NodeChild(value = "right")
@NodeChild(value = "mask", type = LLVMExpressionNode.class)
public abstract class LLVMShuffleVectorNode extends LLVMExpressionNode {
    protected final int resultLength;

    public LLVMShuffleVectorNode(int resultLength) {
        this.resultLength = resultLength;
    }

    public abstract static class LLVMShuffleI8VectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleI8VectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMI8Vector doI8Vector(LLVMI8Vector leftVector, LLVMI8Vector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            byte[] newValues = new byte[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMI8Vector.create(newValues);
        }
    }

    public abstract static class LLVMShuffleI16VectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleI16VectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMI16Vector doI8Vector(LLVMI16Vector leftVector, LLVMI16Vector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            short[] newValues = new short[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMI16Vector.create(newValues);
        }
    }

    public abstract static class LLVMShuffleI32VectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleI32VectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMI32Vector doI32Vector(LLVMI32Vector leftVector, LLVMI32Vector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            int[] newValues = new int[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMI32Vector.create(newValues);
        }
    }

    public abstract static class LLVMShuffleI64VectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleI64VectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMI64Vector doI64Vector(LLVMI64Vector leftVector, LLVMI64Vector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            long[] newValues = new long[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMI64Vector.create(newValues);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMPointerVector doPointerVector(LLVMPointerVector leftVector, LLVMPointerVector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            LLVMPointer[] newValues = new LLVMPointer[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMPointerVector.create(newValues);
        }
    }

    public abstract static class LLVMShuffleFloatVectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleFloatVectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMFloatVector doOp(LLVMFloatVector leftVector, LLVMFloatVector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            float[] newValues = new float[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMFloatVector.create(newValues);
        }
    }

    public abstract static class LLVMShuffleDoubleVectorNode extends LLVMShuffleVectorNode {
        public LLVMShuffleDoubleVectorNode(int resultLength) {
            super(resultLength);
        }

        @Specialization
        @ExplodeLoop
        protected LLVMDoubleVector doOp(LLVMDoubleVector leftVector, LLVMDoubleVector rightVector, LLVMI32Vector maskVector) {
            assert maskVector.getLength() == resultLength;
            double[] newValues = new double[resultLength];
            int leftVectorLength = leftVector.getLength();
            for (int i = 0; i < resultLength; i++) {
                int element = maskVector.getValue(i);
                newValues[i] = element < leftVectorLength ? leftVector.getValue(element) : rightVector.getValue(element - leftVectorLength);
            }
            return LLVMDoubleVector.create(newValues);
        }
    }
}
