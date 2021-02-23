/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.runtime.global;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.interop.LLVMInternalTruffleObject;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMManagedReadLibrary;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMManagedWriteLibrary;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMNativeLibrary;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToNativeNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToPointerNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;

@ExportLibrary(InteropLibrary.class)
@ExportLibrary(LLVMManagedReadLibrary.class)
@ExportLibrary(LLVMManagedWriteLibrary.class)
public final class LLVMGlobalContainer extends LLVMInternalTruffleObject {

    private static final int MAX_CACHED_WRITES = 3;

    private static final class State {
        final Object value;
        final Assumption assumption;
        final int writeCount;

        State(Object value, int writeCount) {
            assert writeCount <= MAX_CACHED_WRITES;
            this.value = value;
            this.writeCount = writeCount;
            this.assumption = Truffle.getRuntime().createAssumption();
        }
    }

    private long address;

    // Only constant-propagate a globals' state when reading. See {@link get()} and {@link set()}
    // below. This essentially means we need two copies of {@link contents}, one for reading from
    // (which is effectively final, i.e. @CompilationFinal) and one for using when updating ({@link
    // contentsNotFinal}).
    @CompilationFinal private State contents;
    private State contentsNotFinal;

    private Object fallbackContents;

    public LLVMGlobalContainer() {
        State state = new State(0L, 0);
        contents = state;
        contentsNotFinal = state;
    }

    public Object get() {
        while (true) {
            State c = contents;
            if (c.assumption.isValid()) {
                return c.value;
            }
            if (c.writeCount == MAX_CACHED_WRITES) {
                return fallbackContents;
            }
            // invalidation in progress, re-read
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
    }

    public Object getFallback() {
        return fallbackContents;
    }

    public void set(Object value, BranchProfile needsInitialize, BranchProfile needsInvalidation) {
        State c = contentsNotFinal;
        if (c.writeCount < MAX_CACHED_WRITES) {
            needsInitialize.enter();
            State state = new State(value, c.writeCount + 1);
            contents = state;
            contentsNotFinal = state;
            c.assumption.invalidate();
        } else {
            if (c.assumption.isValid()) {
                needsInvalidation.enter();
                c.assumption.invalidate();
            }
        }
        /*
         * Note: we always set the 'fallbackContents' because in theory, it could happen that
         * someone writes to this global, then the singleContextAssumption is invalidated and from
         * then on just reads the 'fallbackContents'. The penalty won't be high because we only
         * allow small number of cached writes (i.e. MAX_CACHED_WRITES) in which case we do write
         * the value twice.
         */
        setFallback(value);
    }

    public void setFallback(Object value) {
        fallbackContents = value;
    }

    @ExportMessage
    public boolean isPointer() {
        return address != 0;
    }

    @ExportMessage
    public long asPointer() throws UnsupportedMessageException {
        if (isPointer()) {
            return address;
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    public long getAddress() {
        return address;
    }

    @SuppressWarnings("static-method")
    public int getSize() {
        return 1;
    }

    @TruffleBoundary
    @ExportMessage
    public void toNative(@Cached LLVMToNativeNode toNative) {
        if (address == 0) {
            LLVMMemory memory = LLVMLanguage.getLanguage().getLLVMMemory();
            LLVMNativePointer pointer = memory.allocateMemory(toNative, 8);
            address = pointer.asNative();
            long value;
            Object global = getFallback();
            if (global instanceof Number) {
                value = ((Number) global).longValue();
            } else {
                value = toNative.executeWithTarget(global).asNative();
            }
            memory.putI64(toNative, pointer, value);
        }
    }

    @ExportMessage(name = "isReadable")
    @ExportMessage(name = "isWritable")
    @SuppressWarnings("static-method")
    boolean isAccessible() {
        return true;
    }

    @ExportMessage
    static class ReadI8 {

        @Specialization(guards = "self.isPointer()")
        static byte readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getI8(location, self.getAddress() + offset);
        }

        @Specialization(guards = "!self.isPointer()")
        static byte readManaged(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readI8(self, offset);
        }
    }

    @ExportMessage
    static class ReadI16 {

        @Specialization(guards = "self.isPointer()")
        static short readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getI16(location, self.getAddress() + offset);
        }

        @Specialization(guards = "!self.isPointer()")
        static short readManaged(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readI16(self, offset);
        }
    }

    @ExportMessage
    static class ReadI32 {

        @Specialization(guards = "self.isPointer()")
        static int readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getI32(location, self.getAddress() + offset);
        }

        @Specialization(guards = "!self.isPointer()")
        static int readManaged(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readI32(self, offset);
        }
    }

    @ExportMessage
    static class ReadFloat {

        @Specialization(guards = "self.isPointer()")
        static float readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getFloat(location, self.getAddress() + offset);
        }

        @Specialization(guards = "!self.isPointer()")
        static float readManaged(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readFloat(self, offset);
        }
    }

    @ExportMessage
    static class ReadDouble {

        @Specialization(guards = "self.isPointer()")
        static double readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getDouble(location, self.getAddress() + offset);
        }

        @Specialization(guards = "!self.isPointer()")
        static double readManaged(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readDouble(self, offset);
        }
    }

    @ExportMessage
    static class ReadGenericI64 {

        static Assumption singleContextAssumption() {
            return LLVMLanguage.getLanguage().singleContextAssumption;
        }

        @Specialization(guards = "self.isPointer()")
        static long readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getI64(location, self.getAddress() + offset);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, assumptions = "singleContextAssumption()")
        static Object readI64ManagedSingleContext(LLVMGlobalContainer self, long offset) {
            assert offset == 0;
            return self.get();
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, replaces = "readI64ManagedSingleContext")
        static Object readI64Managed(LLVMGlobalContainer self, long offset) {
            assert offset == 0;
            return self.getFallback();
        }

        @Specialization(guards = {"!self.isPointer()", "offset != 0"})
        static Object readFallback(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readGenericI64(self, offset);
        }
    }

    @ExportMessage
    static class ReadPointer {

        static Assumption singleContextAssumption() {
            return LLVMLanguage.getLanguage().singleContextAssumption;
        }

        @Specialization(guards = "self.isPointer()")
        static LLVMPointer readNative(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") LLVMManagedReadLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            return language.getLLVMMemory().getPointer(location, self.getAddress() + offset);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, assumptions = "singleContextAssumption()")
        static LLVMPointer readManagedSingleContext(LLVMGlobalContainer self, long offset,
                        @Shared("toPointer") @Cached LLVMToPointerNode toPointer) {
            assert offset == 0;
            return toPointer.executeWithTarget(self.get());
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, replaces = "readManagedSingleContext")
        static LLVMPointer readManaged(LLVMGlobalContainer self, long offset,
                        @Shared("toPointer") @Cached LLVMToPointerNode toPointer) {
            assert offset == 0;
            return toPointer.executeWithTarget(self.getFallback());
        }

        @Specialization(guards = {"!self.isPointer()", "offset != 0"})
        static LLVMPointer readFallback(LLVMGlobalContainer self, long offset,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedReadLibrary read) {
            interop.toNative(self);
            return read.readPointer(self, offset);
        }
    }

    @ExportMessage
    static class WriteI8 {

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, byte value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putI8(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = "!self.isPointer()")
        static void writeManaged(LLVMGlobalContainer self, long offset, byte value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeI8(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteI16 {

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, short value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putI16(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = "!self.isPointer()")
        static void writeManaged(LLVMGlobalContainer self, long offset, short value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeI16(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteI32 {

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, int value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putI32(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = "!self.isPointer()")
        static void writeManaged(LLVMGlobalContainer self, long offset, int value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeI32(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteFloat {

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, float value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putFloat(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = "!self.isPointer()")
        static void writeManaged(LLVMGlobalContainer self, long offset, float value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeFloat(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteDouble {

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, double value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putDouble(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = "!self.isPointer()")
        static void writeManaged(LLVMGlobalContainer self, long offset, double value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeDouble(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteI64 {

        static Assumption singleContextAssumption() {
            return LLVMLanguage.getLanguage().singleContextAssumption;
        }

        @Specialization(guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, long value,
                        @CachedLibrary("self") LLVMManagedWriteLibrary location,
                        @CachedLanguage LLVMLanguage language) {
            language.getLLVMMemory().putI64(location, self.getAddress() + offset, value);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, assumptions = "singleContextAssumption()")
        static void writeManagedSingleContext(LLVMGlobalContainer self, long offset, long value,
                        @Shared("p1") @Cached BranchProfile needsInitialize,
                        @Shared("p2") @Cached BranchProfile needsInvalidation) {
            assert offset == 0;
            self.set(value, needsInitialize, needsInvalidation);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, replaces = "writeManagedSingleContext")
        static void writeManaged(LLVMGlobalContainer self, long offset, long value) {
            assert offset == 0;
            self.setFallback(value);
        }

        @Specialization(guards = {"!self.isPointer()", "offset != 0"})
        static void writeFallback(LLVMGlobalContainer self, long offset, long value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeI64(self, offset, value);
        }
    }

    @ExportMessage
    static class WriteGenericI64 {

        static Assumption singleContextAssumption() {
            return LLVMLanguage.getLanguage().singleContextAssumption;
        }

        @Specialization(limit = "3", guards = "self.isPointer()")
        static void writeNative(LLVMGlobalContainer self, long offset, Object value,
                        @CachedLibrary("value") LLVMNativeLibrary toNative,
                        @CachedLanguage LLVMLanguage language) {
            long ptr = toNative.toNativePointer(value).asNative();
            language.getLLVMMemory().putI64(toNative, self.getAddress() + offset, ptr);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, assumptions = "singleContextAssumption()")
        static void writeI64ManagedSingleContext(LLVMGlobalContainer self, long offset, Object value,
                        @Shared("p1") @Cached BranchProfile needsInitialize,
                        @Shared("p2") @Cached BranchProfile needsInvalidation) {
            assert offset == 0;
            self.set(value, needsInitialize, needsInvalidation);
        }

        @Specialization(guards = {"!self.isPointer()", "offset == 0"}, replaces = "writeI64ManagedSingleContext")
        static void writeI64Managed(LLVMGlobalContainer self, long offset, Object value) {
            assert offset == 0;
            self.setFallback(value);
        }

        @Specialization(guards = {"!self.isPointer()", "offset != 0"})
        static void writeFallback(LLVMGlobalContainer self, long offset, Object value,
                        @CachedLibrary("self") InteropLibrary interop,
                        @CachedLibrary("self") LLVMManagedWriteLibrary write) {
            interop.toNative(self);
            write.writeGenericI64(self, offset, value);
        }
    }

    public void dispose() {
        if (address != 0) {
            LLVMMemory memory = LLVMLanguage.getLanguage().getLLVMMemory();
            memory.free(null, address);
            address = 0;
        }
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return String.format("LLVMGlobalContainer (address = 0x%x, contents = %s)", address, contents);
    }

}
