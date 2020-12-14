/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.va;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.PlatformCapability;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.LLVMBuiltin;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;

/**
 * The node handling the <code>va_copy</code> instruction. It just delegates to
 * {@link LLVMVaListLibrary}.
 */
@NodeChild(type = LLVMExpressionNode.class)
@NodeChild(type = LLVMExpressionNode.class)
@NodeField(type = int.class, name = "numberExplicitArguments")
public abstract class LLVMVACopy extends LLVMBuiltin {

    public LLVMVACopy() {
    }

    public abstract int getNumberExplicitArguments();

    @Specialization(limit = "1")
    protected Object copyManaged(LLVMManagedPointer dest, LLVMManagedPointer source,
                    @CachedLibrary("source.getObject()") LLVMVaListLibrary vaListLibrary) {
        vaListLibrary.copy(source.getObject(), dest.getObject());
        return null;
    }

    @Specialization(limit = "1")
    protected Object copyManaged(LLVMNativePointer dest, LLVMManagedPointer source,
                    @CachedLibrary("source.getObject()") LLVMVaListLibrary vaListLibrary) {
        vaListLibrary.copy(source.getObject(), dest);
        return null;
    }

    Object createNativeVAListWrapper(LLVMNativePointer targetAddress, LLVMLanguage lang) {
        return lang.getCapability(PlatformCapability.class).createNativeVAListWrapper(targetAddress, getRootNode());
    }

    @Specialization
    protected Object copyNative(Object dest, LLVMNativePointer source,
                    @CachedLanguage LLVMLanguage lang,
                    @Cached NativeLLVMVaListHelper nativeLLVMVaListHelper) {
        return nativeLLVMVaListHelper.execute(dest, createNativeVAListWrapper(source, lang), getNumberExplicitArguments());
    }

    abstract static class NativeLLVMVaListHelper extends LLVMNode {

        public abstract Object execute(Object dest, Object nativeVaListWrapper, int numberOfExplicitArguments);

        @Specialization(limit = "1")
        protected Object doCopy(Object dest, Object nativeVaListWrapper,
                        @CachedLibrary("nativeVaListWrapper") LLVMVaListLibrary vaListLibrary) {
            vaListLibrary.copy(nativeVaListWrapper, dest);
            return null;
        }

    }
}
