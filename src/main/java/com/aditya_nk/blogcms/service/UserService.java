package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.entity.User;

import java.util.Optional;

/**
 * Service interface for User entity operations.
 * Defines the contract for user management business logic.
 *
 * This interface abstracts the business logic layer from the controller,
 * allowing for easier testing and potential swapping of implementations.
 */
public interface UserService {

    /**
     * Create a new user.
     *
     * @param user the user entity to create
     * @return the created user with generated ID
     */
    User createUser(User user);

    /**
     * Find a user by their email address.
     *
     * @param email the email address to search for
     * @return Optional containing the user if found, empty otherwise
     */
    Optional<User> findByEmail(String email);

    /**
     * Find a user by their ID.
     *
     * @param id the user ID to search for
     * @return Optional containing the user if found, empty otherwise
     */
    Optional<User> findById(Long id);

    /**
     * Update an existing user.
     *
     * @param id the ID of the user to update
     * @param user the user entity containing updated data
     * @return the updated user
     */
    User updateUser(Long id, User user);

    /**
     * Soft delete a user by their ID.
     *
     * @param id the ID of the user to delete
     */
    void deleteUser(Long id);

    /**
     * Check if a user exists with the given email.
     *
     * @param email the email address to check
     * @return true if a user exists with this email, false otherwise
     */
    boolean existsByEmail(String email);
}

