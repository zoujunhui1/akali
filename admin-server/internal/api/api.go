// Package api is the admin system's HTTP interface. Everything except /healthz and the login needs a session.
package api

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/zoujunhui1/akali/admin-server/internal/auth"
	"github.com/zoujunhui1/akali/admin-server/internal/store"
)

const (
	cookieName = "akali_admin_session"
	userKey    = "user"
)

type Config struct {
	// InsecureCookie drops the Secure flag so the login works over plain http while developing locally.
	InsecureCookie bool
	// Articles turns on the article import pages; nil leaves them out.
	Articles *Articles
	// Lessons turns on the listening lesson pages; nil leaves them out.
	Lessons *Lessons
	// Dict turns on the offline dictionary page; nil leaves it out.
	Dict *Dict
	// Oxford turns on the picture dictionary page; nil leaves it out.
	Oxford *Oxford
}

type Server struct {
	cfg  Config
	auth *auth.Service
	jobs Jobs
}

func New(cfg Config, a *auth.Service, j Jobs) (*gin.Engine, error) {
	srv := &Server{cfg: cfg, auth: a, jobs: j}
	r := gin.New()
	// only the Caddy on this machine may tell us the client's address
	if err := r.SetTrustedProxies([]string{"127.0.0.1"}); err != nil {
		return nil, err
	}
	r.Use(gin.Recovery(), gin.Logger(), sameSiteOnly())
	r.GET("/healthz", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	api := r.Group("/api")
	api.POST("/auth/login", srv.login)
	api.POST("/auth/logout", srv.logout)
	api.GET("/auth/me", srv.requireSession(), srv.me)
	srv.jobRoutes(api.Group("", srv.requireSession()))
	if cfg.Articles != nil {
		srv.articleRoutes(api.Group("", srv.requireSession()))
	}
	if cfg.Oxford != nil {
		srv.oxfordRoutes(api.Group("", srv.requireSession()))
	}
	if cfg.Dict != nil {
		srv.dictRoutes(api.Group("", srv.requireSession()))
	}
	if cfg.Lessons != nil {
		srv.lessonRoutes(api.Group("", srv.requireSession()))
	}
	return r, nil
}

// sameSiteOnly refuses writes that do not carry our own header: a page on another site cannot add it
// without a CORS preflight, which we never allow.
func sameSiteOnly() gin.HandlerFunc {
	return func(c *gin.Context) {
		switch c.Request.Method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
			return
		}
		if c.GetHeader("X-Requested-With") != "akali-admin" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "missing X-Requested-With header"})
		}
	}
}

func (s *Server) requireSession() gin.HandlerFunc {
	return func(c *gin.Context) {
		token, _ := c.Cookie(cookieName)
		user, err := s.auth.User(c.Request.Context(), token)
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "not logged in"})
				return
			}
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "server error"})
			return
		}
		c.Set(userKey, user)
	}
}

func (s *Server) setCookie(c *gin.Context, value string, maxAge int) {
	http.SetCookie(c.Writer, &http.Cookie{
		Name: cookieName, Value: value, Path: "/", MaxAge: maxAge,
		HttpOnly: true, Secure: !s.cfg.InsecureCookie, SameSite: http.SameSiteStrictMode,
	})
}

func (s *Server) login(c *gin.Context) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || body.Username == "" || body.Password == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username and password are required"})
		return
	}
	token, err := s.auth.Login(c.Request.Context(), body.Username, body.Password, c.ClientIP())
	switch {
	case errors.Is(err, auth.ErrLocked):
		c.JSON(http.StatusTooManyRequests, gin.H{"error": err.Error()})
	case errors.Is(err, auth.ErrBadLogin):
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
	case err != nil:
		c.JSON(http.StatusInternalServerError, gin.H{"error": "server error"})
	default:
		s.setCookie(c, token, int(auth.SessionTTL.Seconds()))
		c.JSON(http.StatusOK, gin.H{"user": body.Username})
	}
}

func (s *Server) logout(c *gin.Context) {
	if token, err := c.Cookie(cookieName); err == nil {
		_ = s.auth.Logout(c.Request.Context(), token)
	}
	s.setCookie(c, "", -1)
	c.JSON(http.StatusOK, gin.H{})
}

func (s *Server) me(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"user": c.GetString(userKey)})
}
