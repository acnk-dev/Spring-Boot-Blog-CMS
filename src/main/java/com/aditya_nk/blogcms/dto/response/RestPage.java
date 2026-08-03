package com.aditya_nk.blogcms.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * A Jackson-serializable wrapper around Spring's PageImpl.
 *
 * Problem: PageImpl has no default constructor and no @JsonCreator, so Jackson
 * cannot deserialize it when reading back from Redis. Caching any method that
 * returns Page<T> will fail on the second request (cache hit) with:
 *   "Cannot construct instance of PageImpl — no Creators exist"
 *
 * Solution: RestPage adds the @JsonCreator constructor that Jackson needs.
 * Because it extends PageImpl, it IS-A Page — controllers and callers that
 * declare Page<T> as the return type require no changes.
 *
 * @param <T> the element type of this page
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestPage<T> extends PageImpl<T> {

    /**
     * Jackson deserialization constructor.
     * Maps the JSON fields produced by PageImpl's serializer back to the
     * constructor arguments needed to reconstruct the page.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public RestPage(
            @JsonProperty("content") List<T> content,
            @JsonProperty("number") int number,
            @JsonProperty("size") int size,
            @JsonProperty("totalElements") long totalElements) {
        super(content, PageRequest.of(number, Math.max(size, 1)), totalElements);
    }

    /**
     * Convenience constructor for wrapping an existing Page returned by the repository.
     */
    public RestPage(Page<T> page) {
        super(page.getContent(), page.getPageable(), page.getTotalElements());
    }
}

