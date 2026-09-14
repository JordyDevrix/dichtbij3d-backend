CREATE TABLE user_muted_notifications (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, notification_type)
);
