/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AERON_BROADCAST_RECEIVER_H
#define AERON_BROADCAST_RECEIVER_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "util/aeron_bitutil.h"

#pragma pack(push)
#pragma pack(4)
typedef struct aeron_broadcast_descriptor_stct
{
    int64_t tail_intent_counter;
    int64_t tail_counter;
    int64_t latest_counter;
    uint8_t pad[(2 * AERON_CACHE_LINE_LENGTH) - (3 * sizeof(int64_t))];
}
aeron_broadcast_descriptor_t;

typedef struct aeron_broadcast_record_descriptor_stct
{
    int32_t length;
    int32_t msg_type_id;
}
aeron_broadcast_record_descriptor_t;
#pragma pack(pop)

#define AERON_BROADCAST_BUFFER_TRAILER_LENGTH (sizeof(aeron_broadcast_descriptor_t))

#define AERON_BROADCAST_IS_CAPACITY_VALID(capacity) AERON_IS_POWER_OF_TWO(capacity)
#define AERON_BROADCAST_MAX_MESSAGE_LENGTH(capacity) (capacity / 8)
#define AERON_BROADCAST_INVALID_MSG_TYPE_ID(id) (id < 1)
#define AERON_BROADCAST_PADDING_MSG_TYPE_ID (-1)

#define AERON_BROADCAST_RECORD_HEADER_LENGTH (sizeof(aeron_broadcast_record_descriptor_t))
#define AERON_BROADCAST_RECORD_ALIGNMENT (sizeof(aeron_broadcast_record_descriptor_t))

#define AERON_BROADCAST_SCRATCH_BUFFER_LENGTH (4096)

typedef struct aeron_broadcast_receiver_stct
{
    uint8_t scratch_buffer[AERON_BROADCAST_SCRATCH_BUFFER_LENGTH];
    uint8_t *buffer;
    aeron_broadcast_descriptor_t *descriptor;
    size_t capacity;
    size_t mask;

    size_t record_offset;
    int64_t cursor;
    int64_t next_record;
    long lapped_count;
}
aeron_broadcast_receiver_t;

typedef void (*aeron_broadcast_receiver_handler_t)(int32_t type_id, uint8_t *buffer, int offset, int length);

int aeron_broadcast_receiver_init(volatile aeron_broadcast_receiver_t *receiver, void *buffer, size_t length);

/* bool aeron_broadcast_receiver_receive_next(volatile aeron_broadcast_receiver_t *receiver); */

int aeron_broadcast_receiver_receive(
    volatile aeron_broadcast_receiver_t *receiver, aeron_broadcast_receiver_handler_t handler);

#endif //AERON_BROADCAST_RECEIVER_H
