CREATE TABLE advert_lists (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE advert_list_items (
    id UUID PRIMARY KEY,
    list_id UUID NOT NULL REFERENCES advert_lists(id) ON DELETE CASCADE,
    advert_id UUID NOT NULL REFERENCES adverts(id) ON DELETE CASCADE,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (list_id, advert_id)
);
