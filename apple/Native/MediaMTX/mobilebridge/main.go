package main

/*
#include <stdint.h>
#include <stdlib.h>

typedef void (*R2CMediaMTXLogCallback)(uintptr_t context, const char *line);

static inline void R2CMediaMTXInvokeLogCallback(
    R2CMediaMTXLogCallback callback,
    uintptr_t context,
    const char *line
) {
    if (callback != NULL) {
        callback(context, line);
    }
}
*/
import "C"

import (
	"bufio"
	"fmt"
	"os"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/bluenviron/mediamtx/internal/core"
	"github.com/bluenviron/mediamtx/internal/r2cingress"
)

// asyncpreemptoff is not a //go:debug key in Go 1.25. It is also not an atomic
// runtime dbgvar, so os.Setenv("GODEBUG") after schedinit does not change it.
// A C constructor setenv does not reach the c-archive either: on Darwin and
// iOS, _rt0_arm64_ios_lib reads the envp block dyld passed in, and setenv
// installs a new environ instead of editing that block.
//
// parsegodebug is the same parser schedinit uses. With a nil seen map it
// writes non-atomic dbgvars, including asyncpreemptoff, and the preemption
// sites read that variable on every attempt. The iOS c-archive link passes
// -checklinkname=0 so this internal reference is allowed.
//
//go:linkname parsegodebug runtime.parsegodebug
func parsegodebug(godebug string, seen map[string]bool)

// runtime.debug field order in Go 1.25.6. asyncpreemptoff is the 20th int32.
// The read is only for the startup log. The write goes through parsegodebug
// by name, so a future field reorder cannot flip the setting itself.
//
//go:linkname runtimeDebug runtime.debug
var runtimeDebug [20]int32

func init() {
	parsegodebug("asyncpreemptoff=1", nil)
}

func asyncPreemptOffLabel() string {
	if runtime.Version() != "go1.25.6" {
		return "unverified"
	}
	return strconv.Itoa(int(runtimeDebug[19]))
}

var instance struct {
	sync.Mutex
	server      *core.Core
	logWriter   *os.File
	logCallback C.R2CMediaMTXLogCallback
	logContext  C.uintptr_t
}

//export R2CMediaMTXSetLogCallback
func R2CMediaMTXSetLogCallback(callback C.R2CMediaMTXLogCallback, context C.uintptr_t) {
	instance.Lock()
	instance.logCallback = callback
	instance.logContext = context
	instance.Unlock()
}

//export R2CMediaMTXStart
func R2CMediaMTXStart(configPath *C.char) C.int {
	instance.Lock()
	defer instance.Unlock()

	if instance.server != nil {
		return 2
	}

	logReader, logWriter, err := os.Pipe()
	if err != nil {
		return 3
	}

	originalStdout := os.Stdout
	os.Stdout = logWriter
	server, ok := core.New([]string{C.GoString(configPath)})
	os.Stdout = originalStdout
	if !ok {
		logWriter.Close()
		logReader.Close()
		return 1
	}

	instance.server = server
	instance.logWriter = logWriter
	fmt.Fprintf(logWriter, "Go runtime asyncpreemptoff=%s\n", asyncPreemptOffLabel())
	go forwardLogLines(logReader)
	return 0
}

//export R2CMediaMTXStop
func R2CMediaMTXStop() {
	instance.Lock()
	server := instance.server
	logWriter := instance.logWriter
	instance.server = nil
	instance.logWriter = nil
	instance.Unlock()

	if server != nil {
		server.Close()
	}
	if logWriter != nil {
		logWriter.Close()
	}
}

func forwardLogLines(reader *os.File) {
	defer reader.Close()

	// The scanner must keep the stdout pipe empty. A full pipe blocks whichever
	// MediaMTX goroutine is logging, including the RTMP reader between reads.
	lines := make(chan string, 256)
	var dropped atomic.Uint64
	var reported atomic.Uint64
	go deliverLogLines(lines, &dropped, &reported)

	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		select {
		case lines <- line:
		default:
			next := dropped.Add(1)
			r2cingress.NoteLogOverflow(next)
		}
	}
	close(lines)
}

func deliverLogLines(lines <-chan string, dropped, reported *atomic.Uint64) {
	for line := range lines {
		invokeLogLine(line)
		overflow := dropped.Load()
		if overflow != reported.Load() {
			reported.Store(overflow)
			invokeLogLine(fmt.Sprintf("RTMP log buffer overflow dropped=%d", overflow))
		}
	}
}

func invokeLogLine(line string) {
	instance.Lock()
	callback := instance.logCallback
	context := instance.logContext
	instance.Unlock()
	if callback == nil {
		return
	}
	owned := C.CString(line)
	C.R2CMediaMTXInvokeLogCallback(callback, context, owned)
	C.free(unsafe.Pointer(owned))
}

func main() {}
