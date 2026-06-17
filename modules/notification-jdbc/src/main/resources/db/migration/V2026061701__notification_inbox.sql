create table if not exists skeleton_notification_inbox (
    recipient_id varchar(128) not null,
    event_id varchar(128) not null,
    topic varchar(128) not null,
    type varchar(128) not null,
    severity varchar(32) not null,
    title varchar(255),
    message varchar(2000),
    payload_json text not null,
    recipient_ids_json text not null,
    event_created_at datetime(6) not null,
    read_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (recipient_id, event_id),
    index idx_skeleton_notification_inbox_recipient_read_created (
        recipient_id,
        read_at,
        event_created_at
    ),
    index idx_skeleton_notification_inbox_recipient_topic_created (
        recipient_id,
        topic,
        event_created_at
    )
);
