/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

/**
 * Web authentication exception
 *
 */

class WebAuthException extends Exception {

    public WebAuthException(String message) {
        super(message);
    }
}
