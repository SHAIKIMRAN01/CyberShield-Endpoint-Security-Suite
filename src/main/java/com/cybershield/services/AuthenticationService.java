package com.cybershield.services;
import com.cybershield.security.SessionManager;
import com.cybershield.dao.UserDAO;
import com.cybershield.models.User;
import com.cybershield.security.BCryptUtil;

public class AuthenticationService {

    private final UserDAO userDAO = new UserDAO();

    public boolean register(String username,
                            String email,
                            String password) {

        if (userDAO.existsUsername(username)) {

            System.out.println("Username already exists.");

            return false;
        }

        if (userDAO.existsEmail(email)) {

            System.out.println("Email already exists.");

            return false;
        }

        String hash = BCryptUtil.hashPassword(password);

        User user =
                new User(
                        username,
                        email,
                        hash
                );

        return userDAO.saveUser(user);

    }
    public User login(String username,
                  String password) {

    User user =
            userDAO.findByUsername(username);

    if (user == null) {

        System.out.println("User not found.");

        return null;

    }

    boolean valid =
            BCryptUtil.verifyPassword(
                    password,
                    user.getPasswordHash()
            );

    if (!valid) {

        System.out.println("Incorrect password.");

        return null;

    }

    userDAO.updateLastLogin(user.getId());

SessionManager.login(user);

return user;

}
}