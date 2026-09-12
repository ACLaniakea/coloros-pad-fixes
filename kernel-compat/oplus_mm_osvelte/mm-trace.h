/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2018-2024 Oplus. All rights reserved.
 * this referenced by android cutil/trace.h
 */
#ifndef _OSVELTE_MM_TRACE_H
#define _OSVELTE_MM_TRACE_H

/**
 * Maximum size of a message that can be logged to the trace buffer.
 * Note this message includes a tag, the pid, and the string given as the name.
 * Names should be kept short to get the most use of the trace buffer.
 */
#define MM_TRACE_MESSAGE_LENGTH 512

/* reserve 32 for format_begin & format_end */
#define MM_TRACE_FMT_MESSAGE_LENGTH (MM_TRACE_MESSAGE_LENGTH - 32)

static noinline void tracing_mark_write(const char *buf)
{
	trace_puts(buf);
}

#define WRITE_MSG(format_begin, format_end, track_name, name, value) { \
	char buf[MM_TRACE_MESSAGE_LENGTH] __attribute__((uninitialized));     \
	const char *track_name_sep = track_name[0] != '\0' ? "|" : ""; \
	int pid = current->tgid; \
	int len = snprintf(buf, sizeof(buf), format_begin "%s%s%s" format_end, \
			   pid, track_name, track_name_sep, name, value); \
	if (len >= (int) sizeof(buf)) { \
		int name_len = strlen(name) - (len - sizeof(buf)) - 1; \
		/* Truncate the name to make the message fit. */ \
		if (name_len > 0) { \
			len = snprintf(buf, sizeof(buf), format_begin "%s%s%.*s" format_end, pid, \
				       track_name, track_name_sep, name_len, name, value); \
		} else { \
			int track_name_len = 0; \
			if (track_name[0] != '\0') { \
				track_name_len = strlen(track_name) - (len - strlen(name) - sizeof(buf)) - 2; \
			} \
			if (track_name_len <= 0) { \
				/* Data is still too long. Drop it. */ \
				len = 0; \
			} else { \
				/* Truncate the trackName and name to make the message fit */ \
				len = snprintf(buf, sizeof(buf), format_begin "%.*s|%.1s" format_end, pid, \
					       track_name_len, track_name, name, value); \
			} \
		} \
	} \
	if (len > 0) { \
		tracing_mark_write(buf); \
	} \
}

static void mm_trace_begin_body(const char *name)
{
	WRITE_MSG("B|%d|", "%s", "", name, "");
}

static void mm_trace_end_body(void)
{
	WRITE_MSG("E|%d", "%s", "", "", "");
}

static void mm_trace_async_begin_body(const char *name, int32_t cookie)
{
	WRITE_MSG("S|%d|", "|%d", "", name, cookie);
}

static void mm_trace_async_end_body(const char *name, int32_t cookie)
{
	WRITE_MSG("F|%d|", "|%d", "", name, cookie);
}

static void mm_trace_int64_body(const char *name, int64_t value)
{
	WRITE_MSG("C|%d|", "|%lld", "", name, value);
}

/**
 * Trace the beginning of a context.  name is used to identify the context.
 * This is often used to time function execution.
 */
static inline void mm_trace_begin(const char *name)
{
	mm_trace_begin_body(name);
}

/**
 * Trace the end of a context.
 * This should match up (and occur after) a corresponding MM_TRACE_BEGIN.
 */
static inline void mm_trace_end(void)
{
	mm_trace_end_body();
}

static inline void mm_trace_fmt_begin(const char *fmt, ...)
{
	char buf[MM_TRACE_FMT_MESSAGE_LENGTH];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(buf, MM_TRACE_FMT_MESSAGE_LENGTH, fmt, ap);
	va_end(ap);

	mm_trace_begin(buf);
}

static inline void mm_trace_fmt_end(void)
{
	mm_trace_end();
}

/**
 * Trace the beginning of an asynchronous event. Unlike MM_TRACE_BEGIN/MM_TRACE_END
 * contexts, asynchronous events do not need to be nested. The name describes
 * the event, and the cookie provides a unique identifier for distinguishing
 * simultaneous events. The name and cookie used to begin an event must be
 * used to end it.
 */
static inline void mm_trace_async_begin(const char *name, int32_t cookie)
{
	mm_trace_async_begin_body(name, cookie);
}

/**
 * Trace the end of an asynchronous event.
 * This should have a corresponding MM_TRACE_ASYNC_BEGIN.
 */
static inline void mm_trace_async_end(const char *name, int32_t cookie)
{
	mm_trace_async_end_body(name, cookie);
}

static inline void mm_trace_fmt_async_begin(int32_t cookie,
					    const char *fmt, ...)
{
	char buf[MM_TRACE_FMT_MESSAGE_LENGTH];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(buf, MM_TRACE_FMT_MESSAGE_LENGTH, fmt, ap);
	va_end(ap);

	mm_trace_async_begin(buf, cookie);
}

static inline void mm_trace_fmt_async_end(int32_t cookie,
					  const char *fmt, ...)
{
	char buf[MM_TRACE_FMT_MESSAGE_LENGTH];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(buf, MM_TRACE_FMT_MESSAGE_LENGTH, fmt, ap);
	va_end(ap);

	mm_trace_async_end(buf, cookie);
}

/**
 * Traces a 64-bit integer counter value.  name is used to identify the
 * counter. This can be used to track how a value changes over time.
 */
static inline void mm_trace_int64(const char *name, int64_t value)
{
	mm_trace_int64_body(name, value);
}

static inline void mm_trace_fmt_int64(int64_t value,
				      const char *fmt, ...)
{
	char buf[MM_TRACE_FMT_MESSAGE_LENGTH];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(buf, MM_TRACE_FMT_MESSAGE_LENGTH, fmt, ap);
	va_end(ap);

	mm_trace_int64(buf, value);
}
#endif /* _OSVELTE_MM_TRACE_H */
